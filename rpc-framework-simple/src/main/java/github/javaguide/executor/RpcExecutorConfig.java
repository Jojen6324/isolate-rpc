package github.javaguide.executor;
import java.util.*;
public final class RpcExecutorConfig {
    public enum Mode { SHARED, ISOLATED }
    public static final class Limits {
        public final int threads, queue;
        public Limits(int threads, int queue) {
            if (threads < 1 || queue < 1) throw new IllegalArgumentException("Threads and queue must be positive");
            this.threads = threads; this.queue = queue;
        }
    }
    public final Mode mode; public final Limits shared, isolated; public final long shutdownMillis;
    private final Map<ServiceKey, Limits> overrides;
    public RpcExecutorConfig(Mode mode, int threads, int queue, int perThreads, int perQueue, long shutdownMillis) {
        this(mode, new Limits(threads, queue), new Limits(perThreads, perQueue), shutdownMillis, Collections.emptyMap());
    }
    private RpcExecutorConfig(Mode mode, Limits shared, Limits isolated, long shutdownMillis, Map<ServiceKey, Limits> overrides) {
        if (shutdownMillis < 0) throw new IllegalArgumentException("Invalid shutdown wait");
        this.mode = Objects.requireNonNull(mode); this.shared = shared; this.isolated = isolated; this.shutdownMillis = shutdownMillis;
        this.overrides = Collections.unmodifiableMap(new HashMap<>(overrides));
    }
    public RpcExecutorConfig withService(ServiceKey key, int threads, int queue) {
        Map<ServiceKey, Limits> copy = new HashMap<>(overrides); copy.put(key, new Limits(threads, queue));
        return new RpcExecutorConfig(mode, shared, isolated, shutdownMillis, copy);
    }
    public Limits limits(ServiceKey key) { return overrides.getOrDefault(key, isolated); }
    public static RpcExecutorConfig defaults() { return new RpcExecutorConfig(Mode.SHARED, 16, 200, 8, 100, 5000); }
}
