package github.javaguide.remoting.transport.netty;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.executor.*;
import github.javaguide.exception.RpcCallException;
import github.javaguide.remoting.dto.*;
import github.javaguide.remoting.transport.netty.client.NettyRpcClient;
import github.javaguide.remoting.transport.netty.server.NettyRpcServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static github.javaguide.exception.RpcCallException.Kind.*;
public class ServiceIsolationTest {
    public interface TestService { String call(String value); }
    public static class ControlledService implements TestService {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public String call(String value) {
            if (value.equals("block")) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
            if (value.equals("error")) throw new IllegalArgumentException("business");
            return value.equals("null") ? null : value;
        }
    }
    private static final AtomicInteger IDS = new AtomicInteger();
    private RpcRequest request(String group, String value) {
        return RpcRequest.builder().interfaceName(TestService.class.getCanonicalName()).group(group).version("v1")
            .methodName("call").paramTypes(new Class<?>[]{String.class}).parameters(new Object[]{value}).requestId("tcp-"+IDS.incrementAndGet()).build();
    }
    private RpcServiceConfig config(String group, ControlledService service) { return RpcServiceConfig.builder().service(service).group(group).version("v1").build(); }
    private RpcCallException failure(CompletableFuture<?> future) {
        return (RpcCallException)assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause();
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) java.util.concurrent.locks.LockSupport.parkNanos(1000000);
        assertTrue(condition.getAsBoolean());
    }
    @Test void twoIndependentClientsNullErrorAndFollowingSuccessOverRealTcp() throws Exception {
        ControlledService service=new ControlledService();
        try(NettyRpcServer server=new NettyRpcServer(Arrays.asList(config("fast",service)),new InetSocketAddress("127.0.0.1",0),RpcExecutorConfig.defaults())) {
            InetSocketAddress address=server.startAsync();
            try(NettyRpcClient a=new NettyRpcClient(r->address);NettyRpcClient b=new NettyRpcClient(r->address)) {
                CompletableFuture<RpcResponse<Object>> fa=a.sendRpcRequestAsync(request("fast","a")),fb=b.sendRpcRequestAsync(request("fast","b"));
                assertEquals("a",fa.get(2,TimeUnit.SECONDS).getData());assertEquals("b",fb.get(2,TimeUnit.SECONDS).getData());
                assertNull(a.sendRpcRequestAsync(request("fast","null")).get(2,TimeUnit.SECONDS).getData());
                assertEquals(BUSINESS,failure(a.sendRpcRequestAsync(request("fast","error"))).getKind());
                assertEquals("healthy",a.sendRpcRequestAsync(request("fast","healthy")).get(2,TimeUnit.SECONDS).getData());
                assertEquals(0,a.pendingCount());assertEquals(0,b.pendingCount());
            }
        }
    }
    @Test void slowOverloadRejectsAndFastServiceIsolatedUsingSameDispatch() throws Exception {
        for(RpcExecutorConfig.Mode mode:RpcExecutorConfig.Mode.values()) {
            ControlledService fast=new ControlledService(),slow=new ControlledService();
            RpcExecutorConfig config=new RpcExecutorConfig(mode,1,1,1,1,100);
            try(NettyRpcServer server=new NettyRpcServer(Arrays.asList(config("fast",fast),config("slow",slow)),new InetSocketAddress("127.0.0.1",0),config)) {
                InetSocketAddress address=server.startAsync();
                try(NettyRpcClient client=new NettyRpcClient(r->address)) {
                    CompletableFuture<?> running=client.sendRpcRequestAsync(request("slow","block"));assertTrue(slow.entered.await(2,TimeUnit.SECONDS));
                    CompletableFuture<?> queued=client.sendRpcRequestAsync(request("slow","queued"));
                    until(()->server.metrics().get(new ServiceKey(TestService.class.getCanonicalName(),"slow","v1")).queued==1);
                    assertEquals(OVERLOADED,failure(client.sendRpcRequestAsync(request("slow","rejected"))).getKind());
                    CompletableFuture<RpcResponse<Object>> quick=client.sendRpcRequestAsync(request("fast","quick"));
                    if(mode==RpcExecutorConfig.Mode.ISOLATED)assertEquals("quick",quick.get(2,TimeUnit.SECONDS).getData());
                    else assertEquals(OVERLOADED,failure(quick).getKind());
                    for(ExecutorMetrics metric:server.metrics().values())assertTrue(metric.queued<=metric.queueCapacity);
                    slow.release.countDown();running.get(2,TimeUnit.SECONDS);queued.get(2,TimeUnit.SECONDS);
                    assertEquals(0,client.pendingCount());assertEquals(0,client.timeoutTaskCount());
                } finally {slow.release.countDown();}
            }
        }
    }
    @Test void timeoutLateResponseDoesNotBreakConnection() throws Exception {
        ControlledService service=new ControlledService();
        try(NettyRpcServer server=new NettyRpcServer(Arrays.asList(config("fast",service)),new InetSocketAddress("127.0.0.1",0),RpcExecutorConfig.defaults())) {
            InetSocketAddress address=server.startAsync();
            try(NettyRpcClient client=new NettyRpcClient(r->address)) {
                CompletableFuture<?> f=client.sendRpcRequestAsync(request("fast","block"),100);assertTrue(service.entered.await(1,TimeUnit.SECONDS));
                assertEquals(TIMEOUT,failure(f).getKind());service.release.countDown();until(()->client.lateResponseCount()==1);
                assertEquals("ok",client.sendRpcRequestAsync(request("fast","ok")).get(2,TimeUnit.SECONDS).getData());
                assertEquals(0,client.pendingCount());assertEquals(0,client.timeoutTaskCount());
            } finally {service.release.countDown();}
        }
    }
    @Test void closeAConnectionDoesNotFailBAndAllowsReconnect() throws Exception {
        ControlledService service=new ControlledService();
        try(NettyRpcServer server=new NettyRpcServer(Arrays.asList(config("fast",service)),new InetSocketAddress("127.0.0.1",0),RpcExecutorConfig.defaults())) {
            InetSocketAddress address=server.startAsync();
            try(NettyRpcClient a=new NettyRpcClient(r->address);NettyRpcClient b=new NettyRpcClient(r->address)) {
                CompletableFuture<?> blocked=a.sendRpcRequestAsync(request("fast","block"));assertTrue(service.entered.await(2,TimeUnit.SECONDS));
                a.getChannel(address).close().syncUninterruptibly();assertEquals(TRANSPORT,failure(blocked).getKind());
                assertEquals("b",b.sendRpcRequestAsync(request("fast","b")).get(2,TimeUnit.SECONDS).getData());
                assertEquals("reconnected",a.sendRpcRequestAsync(request("fast","reconnected")).get(2,TimeUnit.SECONDS).getData());
                assertEquals(0,a.pendingCount());assertEquals(0,b.pendingCount());service.release.countDown();
            } finally {service.release.countDown();}
        }
    }
    @Test void serviceIdentityDoesNotUseAmbiguousConcatenationAndShutdownRejects() throws Exception {
        ServiceKey a=new ServiceKey("interface","ab","c"),b=new ServiceKey("interface","a","bc");assertNotEquals(a,b);
        ServiceExecutorManager manager=new ServiceExecutorManager(Arrays.asList(a,b),new RpcExecutorConfig(RpcExecutorConfig.Mode.ISOLATED,2,2,1,1,100));
        CountDownLatch entered=new CountDownLatch(1),done=new CountDownLatch(1);AtomicInteger abandoned=new AtomicInteger();
        manager.execute(a,()->{entered.countDown();try{done.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},()->{});
        assertTrue(entered.await(1,TimeUnit.SECONDS));manager.execute(a,()->fail("Queued work must not run"),abandoned::incrementAndGet);
        manager.close();assertEquals(1,abandoned.get());
        assertThrows(RejectedExecutionException.class,()->manager.execute(b,()->{},()->{}));manager.close();
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("rpc-business-")));
    }
}
