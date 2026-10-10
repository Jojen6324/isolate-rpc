package github.javaguide.benchmark.adapter;
import github.javaguide.benchmark.api.*;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.executor.RpcExecutorConfig;
import github.javaguide.remoting.transport.netty.server.NettyRpcServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
public class GuideRpcAdapterTest {
    public static class EchoFixture implements BenchmarkService {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public EchoResponse echo(EchoRequest request){
            if("block".equals(request.behavior)){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            return new EchoResponse(request.id,request.payload);
        }
    }
    @Test void actualConnectionsContentChecksAndCancellationPropagateToTransport() throws Exception {
        EchoFixture fixture=new EchoFixture();
        RpcServiceConfig service=RpcServiceConfig.builder().service(fixture).group("fast").version("v1").build();
        try(NettyRpcServer server=new NettyRpcServer(Arrays.asList(service),new InetSocketAddress("127.0.0.1",0),RpcExecutorConfig.defaults())){
            InetSocketAddress address=server.startAsync();
            try(GuideRpcAdapter adapter=new GuideRpcAdapter("127.0.0.1",address.getPort(),2,3000)){
                assertEquals(2,adapter.connectionCount());
                assertEquals("one",adapter.invoke("fast",new EchoRequest("one",new byte[]{3})).id);
                assertEquals("two",adapter.invokeAsync("fast",new EchoRequest("two",new byte[]{4})).get(2,TimeUnit.SECONDS).id);
                EchoRequest blocked=new EchoRequest("cancel",new byte[]{5});blocked.behavior="block";
                CompletableFuture<?> future=adapter.invokeAsync("fast",blocked);assertTrue(fixture.entered.await(2,TimeUnit.SECONDS));
                assertTrue(future.cancel(false));assertEquals(0,adapter.pendingCount());assertEquals(0,adapter.timerCount());fixture.release.countDown();
            }finally{fixture.release.countDown();}
        }
    }
}
