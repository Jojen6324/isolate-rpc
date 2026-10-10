package github.javaguide.remoting.transport.netty.client;

import github.javaguide.enums.*;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.*;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.remoting.transport.netty.codec.*;
import github.javaguide.exception.RpcCallException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static github.javaguide.exception.RpcCallException.Kind.*;

/** Async Netty transport. Deadline includes discovery and connection, without automatic retries. */
public final class NettyRpcClient implements RpcRequestTransport, AutoCloseable {
    private final ServiceDiscovery discovery;
    private final UnprocessedRequests requests = new UnprocessedRequests();
    private final ConcurrentMap<InetSocketAddress, CompletableFuture<Channel>> channels = new ConcurrentHashMap<>();
    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final Bootstrap bootstrap;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, threads("rpc-timeout"));
    private final ThreadPoolExecutor preparation = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256), threads("rpc-discovery"), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed = new AtomicBoolean();
    private static ThreadFactory threads(String name) {
        AtomicInteger ids = new AtomicInteger(); return r -> new Thread(r, name + "-" + ids.incrementAndGet());
    }
    public NettyRpcClient() {
        this(ExtensionLoader.getExtensionLoader(ServiceDiscovery.class).getExtension(ServiceDiscoveryEnum.ZK.getName()));
    }
    public NettyRpcClient(ServiceDiscovery discovery) {
        this.discovery = discovery; timer.setRemoveOnCancelPolicy(true);
        bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000).option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS),
                        new RpcMessageFrameDecoder(), new RpcMessageCodec(), new NettyRpcClientHandler(requests));
                }
            });
    }
    public CompletableFuture<RpcResponse<Object>> sendRpcRequestAsync(RpcRequest request) {
        return sendRpcRequestAsync(request, 3000);
    }
    public CompletableFuture<RpcResponse<Object>> sendRpcRequestAsync(RpcRequest request, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
        String id = request.getRequestId();
        CompletableFuture<RpcResponse<Object>> result = requests.register(id);
        if (result.isDone()) return result;
        try {
            requests.attachTimer(id, timer.schedule(() -> requests.fail(id,
                new RpcCallException(TIMEOUT, "Request deadline exceeded: " + id)), timeoutMillis, TimeUnit.MILLISECONDS));
            Future<?> task = preparation.submit(() -> {
                if (!requests.contains(id)) return;
                try {
                    InetSocketAddress address = discovery.lookupService(request);
                    if (!requests.contains(id)) return;
                    connectAsync(address).whenComplete((channel, error) -> {
                        if (error != null) { requests.fail(id, new RpcCallException(TRANSPORT, "Connect failed", error)); return; }
                        dispatch(request, channel);
                    });
                } catch (RuntimeException e) { requests.fail(id, new RpcCallException(TRANSPORT, "Discovery failed", e)); }
            });
            requests.attachPreparation(id, task);
            result.whenComplete((r,e) -> preparation.purge());
        } catch (RejectedExecutionException e) { requests.fail(id, new RpcCallException(closed.get() ? CLOSED : OVERLOADED, "Client submission rejected", e)); }
        return result;
    }
    /** Explicit channel path, also useful for independently managed connections. */
    public CompletableFuture<RpcResponse<Object>> sendRpcRequestAsync(RpcRequest request, long timeoutMillis, Channel channel) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
        String id = request.getRequestId();
        CompletableFuture<RpcResponse<Object>> f = requests.register(id);
        if (f.isDone()) return f;
        try {
            requests.attachTimer(id, timer.schedule(() -> requests.fail(id, new RpcCallException(TIMEOUT, "Request deadline exceeded")), timeoutMillis, TimeUnit.MILLISECONDS));
            dispatch(request, channel);
        } catch (RejectedExecutionException e) { requests.fail(id, new RpcCallException(CLOSED, "Client closed", e)); }
        return f;
    }
    private void dispatch(RpcRequest request, Channel channel) {
        String id = request.getRequestId();
        if (!requests.bind(id, channel)) return;
        // Check again immediately before submission; cancellation is local and cannot retract an already queued write.
        if (!requests.contains(id)) return;
        try {
            channel.writeAndFlush(RpcMessage.builder().data(request).codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode()).messageType(RpcConstants.REQUEST_TYPE).build())
                .addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) requests.fail(id, new RpcCallException(TRANSPORT, "Write failed", f.cause()));
                });
        } catch (RuntimeException e) { requests.fail(id, new RpcCallException(TRANSPORT, "Write failed", e)); }
    }
    public CompletableFuture<Channel> connectAsync(InetSocketAddress address) {
        if (closed.get()) return failed(new RpcCallException(CLOSED, "Client closed"));
        CompletableFuture<Channel> existing = channels.get(address);
        if (existing != null) return existing;
        CompletableFuture<Channel> created = new CompletableFuture<>();
        existing = channels.putIfAbsent(address, created);
        if (existing != null) return existing;
        created.whenComplete((ch, error) -> { if (error != null) channels.remove(address, created); });
        try {
            ChannelFuture attempt = bootstrap.connect(address);
            attempt.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) { created.completeExceptionally(f.cause()); return; }
                Channel ch = f.channel();
                ch.closeFuture().addListener(done -> channels.remove(address, created));
                if (closed.get()) { ch.close(); created.completeExceptionally(new RpcCallException(CLOSED, "Client closed")); }
                else created.complete(ch);
            });
        } catch (RuntimeException e) { created.completeExceptionally(e); }
        return created;
    }
    private static <T> CompletableFuture<T> failed(Throwable cause) {
        CompletableFuture<T> f = new CompletableFuture<>(); f.completeExceptionally(cause); return f;
    }
    public Channel doConnect(InetSocketAddress address) { return getChannel(address); }
    public Channel getChannel(InetSocketAddress address) {
        try { return connectAsync(address).get(4, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RpcCallException(TRANSPORT, "Connect interrupted", e); }
        catch (ExecutionException | TimeoutException e) { throw new RpcCallException(TRANSPORT, "Connect failed", e); }
    }
    @Override public Object sendRpcRequest(RpcRequest request) {
        CompletableFuture<RpcResponse<Object>> f = sendRpcRequestAsync(request);
        try { return f.get(); }
        catch (InterruptedException e) { f.cancel(false); Thread.currentThread().interrupt(); throw new RpcCallException(TRANSPORT, "Interrupted", e); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
            throw new RpcCallException(TRANSPORT, "Request failed", e.getCause());
        }
    }
    public int pendingCount() { return requests.size(); }
    public int timeoutTaskCount() { return timer.getQueue().size(); }
    public long lateResponseCount() { return requests.lateResponses(); }
    public int connectionCount() { return channels.size(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        requests.close(); preparation.shutdownNow(); timer.shutdownNow();
        for (CompletableFuture<Channel> f : channels.values()) f.thenAccept(Channel::close);
        channels.clear(); group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        try { if (!preparation.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("Discovery tasks did not stop");
            if (!timer.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("Timer did not stop"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Close interrupted", e); }
    }
}
