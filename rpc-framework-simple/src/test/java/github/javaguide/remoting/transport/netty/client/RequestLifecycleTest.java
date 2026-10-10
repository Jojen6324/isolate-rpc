package github.javaguide.remoting.transport.netty.client;
import github.javaguide.remoting.dto.*;
import github.javaguide.exception.RpcCallException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static github.javaguide.exception.RpcCallException.Kind.*;
class RequestLifecycleTest {
    private RpcRequest request(String id) { return RpcRequest.builder().requestId(id).build(); }
    private RpcCallException failure(CompletableFuture<?> f) throws Exception {
        return (RpcCallException)assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS)).getCause();
    }
    @Test void timeoutAndCancellationCleanPendingAndTimers() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        try (NettyRpcClient client = new NettyRpcClient(r -> new InetSocketAddress("127.0.0.1", 1))) {
            CompletableFuture<?> timed = client.sendRpcRequestAsync(request("timeout"), 30, channel);
            assertEquals(TIMEOUT, failure(timed).getKind()); assertEquals(0, client.pendingCount()); assertEquals(0, client.timeoutTaskCount());
            CompletableFuture<?> cancelled = client.sendRpcRequestAsync(request("cancel"), 60000, channel);
            assertTrue(cancelled.cancel(false)); assertTrue(cancelled.isCancelled());
            assertEquals(0, client.pendingCount()); assertEquals(0, client.timeoutTaskCount());
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void writeFailureCleansPending() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) { promise.setFailure(new IllegalStateException("injected write failure")); }
        });
        try (NettyRpcClient client = new NettyRpcClient(r -> new InetSocketAddress("127.0.0.1", 1))) {
            assertEquals(TRANSPORT, failure(client.sendRpcRequestAsync(request("write"), 1000, channel)).getKind());
            assertEquals(0, client.pendingCount()); assertEquals(0, client.timeoutTaskCount());
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void connectionRefusalTerminates() throws Exception {
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        final int unused = port;
        try (NettyRpcClient client = new NettyRpcClient(r -> new InetSocketAddress("127.0.0.1", unused))) {
            assertEquals(TRANSPORT, failure(client.sendRpcRequestAsync(request("refused"), 1500)).getKind());
            assertEquals(0, client.pendingCount()); assertEquals(0, client.timeoutTaskCount());
        }
    }
    @Test void deadlineIncludesBlockedDiscoveryAndDoesNotSendAfterExpiry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (NettyRpcClient client = new NettyRpcClient(r -> {
            entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new InetSocketAddress("127.0.0.1", 1);
        })) {
            CompletableFuture<?> f = client.sendRpcRequestAsync(request("discovery"), 50);
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(TIMEOUT, failure(f).getKind());
            release.countDown(); assertEquals(0, client.pendingCount()); assertEquals(0, client.connectionCount());
        } finally { release.countDown(); }
    }
    @Test void connectionAFailureLeavesBIntactAndLateResponseIsIgnored() throws Exception {
        UnprocessedRequests requests = new UnprocessedRequests();
        EmbeddedChannel a = new EmbeddedChannel(new NettyRpcClientHandler(requests));
        EmbeddedChannel b = new EmbeddedChannel(new NettyRpcClientHandler(requests));
        CompletableFuture<RpcResponse<Object>> fa = requests.register("a"), fb = requests.register("b");
        requests.bind("a", a); requests.bind("b", b); a.close();
        assertEquals(TRANSPORT, failure(fa).getKind()); assertFalse(fb.isDone()); assertEquals(1, requests.size());
        requests.complete(RpcResponse.success("b", "b"), b); assertEquals("b", fb.get().getData());
        requests.complete(RpcResponse.success("late", "a"), b); assertTrue(b.isActive()); assertEquals(1, requests.lateResponses());
        assertEquals(0, requests.size()); a.finishAndReleaseAll(); b.finishAndReleaseAll();
    }
    @Test void terminalBeforeTimerOrChannelAttachmentCannotResurrectRequest() {
        UnprocessedRequests requests = new UnprocessedRequests();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1); scheduler.setRemoveOnCancelPolicy(true);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            CompletableFuture<?> f = requests.register("early"); f.cancel(false);
            ScheduledFuture<?> timer = scheduler.schedule(() -> {}, 1, TimeUnit.DAYS);
            requests.attachTimer("early", timer); assertTrue(timer.isCancelled());
            assertFalse(requests.bind("early", channel)); assertEquals(0, requests.size()); assertEquals(0, scheduler.getQueue().size());
        } finally { scheduler.shutdownNow(); channel.finishAndReleaseAll(); }
    }
    @Test void responseTimeoutCancelRaceHasOneWinnerAndNoResources() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(3);
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1); scheduler.setRemoveOnCancelPolicy(true);
        EmbeddedChannel channel = new EmbeddedChannel(); UnprocessedRequests requests = new UnprocessedRequests();
        try {
            for (int i = 0; i < 1000; i++) {
                String id = "race-"+i; CompletableFuture<?> f = requests.register(id); requests.bind(id, channel);
                requests.attachTimer(id, scheduler.schedule(() -> {}, 1, TimeUnit.DAYS));
                CountDownLatch start = new CountDownLatch(1);
                Future<?> a = racers.submit(() -> { await(start); requests.complete(RpcResponse.success("ok", id), channel); });
                Future<?> b = racers.submit(() -> { await(start); requests.fail(id, new RpcCallException(TIMEOUT, "timeout")); });
                Future<?> c = racers.submit(() -> { await(start); f.cancel(false); });
                start.countDown(); a.get(); b.get(); c.get();
                assertTrue(f.isDone()); assertEquals(0, requests.size()); assertEquals(0, scheduler.getQueue().size());
            }
        } finally { racers.shutdownNow(); scheduler.shutdownNow(); channel.finishAndReleaseAll(); }
    }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    @Test void closeTerminatesInflightRejectsNewAndStopsOwnedThreads() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(); NettyRpcClient client = new NettyRpcClient(r -> new InetSocketAddress("127.0.0.1", 1));
        CompletableFuture<?> f = client.sendRpcRequestAsync(request("closing"), 60000, channel); client.close(); client.close();
        assertEquals(CLOSED, failure(f).getKind()); assertEquals(0, client.pendingCount()); assertEquals(0, client.timeoutTaskCount());
        assertEquals(CLOSED, failure(client.sendRpcRequestAsync(request("new"))).getKind());
        channel.finishAndReleaseAll();
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && (t.getName().startsWith("rpc-timeout-") || t.getName().startsWith("rpc-discovery-"))));
    }
}
