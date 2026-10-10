package github.javaguide.remoting.transport.netty.client;

import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.exception.RpcCallException;
import io.netty.channel.Channel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static github.javaguide.exception.RpcCallException.Kind.*;

/** Per-client registry. Resources detach BEFORE future completion and user callbacks run outside the monitor. */
public class UnprocessedRequests {
    private final class RequestFuture extends CompletableFuture<RpcResponse<Object>> {
        final String id;
        RequestFuture(String id) { this.id = id; }
        @Override public boolean complete(RpcResponse<Object> value) { return finish(id, this, () -> super.complete(value)); }
        @Override public boolean completeExceptionally(Throwable cause) { return finish(id, this, () -> super.completeExceptionally(cause)); }
        @Override public boolean cancel(boolean interrupt) { return finish(id, this, () -> super.cancel(interrupt)); }
    }
    private static final class Pending {
        final CompletableFuture<RpcResponse<Object>> future;
        Channel channel; ScheduledFuture<?> timer; Future<?> preparation;
        Pending(CompletableFuture<RpcResponse<Object>> future) { this.future = future; }
    }
    private final Map<String, Pending> pending = new HashMap<>();
    private final AtomicLong lateResponses = new AtomicLong();
    private boolean closed;
    public synchronized CompletableFuture<RpcResponse<Object>> register(String id) {
        if (id == null || pending.containsKey(id)) throw new IllegalArgumentException("Unique non-null requestId required");
        if (closed) {
            CompletableFuture<RpcResponse<Object>> f = new CompletableFuture<>();
            f.completeExceptionally(new RpcCallException(CLOSED, "Client closed")); return f;
        }
        RequestFuture future = new RequestFuture(id); pending.put(id, new Pending(future)); return future;
    }
    private boolean finish(String id, CompletableFuture<RpcResponse<Object>> future, BooleanSupplier complete) {
        synchronized (this) {
            Pending p = pending.get(id);
            if (p == null || p.future != future) return false;
            pending.remove(id);
            if (p.timer != null) p.timer.cancel(false);
            if (p.preparation != null) p.preparation.cancel(false);
        }
        return complete.getAsBoolean();
    }
    public synchronized void attachTimer(String id, ScheduledFuture<?> timer) {
        Pending p = pending.get(id); if (p == null) timer.cancel(false); else p.timer = timer;
    }
    public synchronized void attachPreparation(String id, Future<?> preparation) {
        Pending p = pending.get(id); if (p == null) preparation.cancel(false); else p.preparation = preparation;
    }
    public boolean bind(String id, Channel channel) {
        synchronized (this) {
            Pending p = pending.get(id); if (p == null) return false; p.channel = channel;
            if (channel.isActive()) return true;
        }
        fail(id, new RpcCallException(TRANSPORT, "Channel inactive")); return false;
    }
    public synchronized boolean contains(String id) { return pending.containsKey(id); }
    public void fail(String id, Throwable cause) {
        Pending p; synchronized (this) { p = pending.get(id); }
        if (p != null) p.future.completeExceptionally(cause);
    }
    public void complete(RpcResponse<Object> response, Channel channel) {
        Pending p;
        synchronized (this) {
            p = pending.get(response.getRequestId());
            if (p == null || p.channel != channel) { lateResponses.incrementAndGet(); return; }
        }
        int code = response.getCode() == null ? 500 : response.getCode();
        boolean won = code == 200 ? p.future.complete(response) : p.future.completeExceptionally(new RpcCallException(
            code == 503 ? OVERLOADED : code == 504 ? CLOSED : BUSINESS, response.getMessage()));
        if (!won) lateResponses.incrementAndGet();
    }
    public void failChannel(Channel channel, Throwable cause) {
        List<Pending> matches = new ArrayList<>();
        synchronized (this) { for (Pending p : pending.values()) if (p.channel == channel) matches.add(p); }
        for (Pending p : matches) p.future.completeExceptionally(cause);
    }
    public void close() {
        List<Pending> all;
        synchronized (this) { closed = true; all = new ArrayList<>(pending.values()); }
        for (Pending p : all) p.future.completeExceptionally(new RpcCallException(CLOSED, "Client closed"));
    }
    public synchronized int size() { return pending.size(); }
    public long lateResponses() { return lateResponses.get(); }
}
