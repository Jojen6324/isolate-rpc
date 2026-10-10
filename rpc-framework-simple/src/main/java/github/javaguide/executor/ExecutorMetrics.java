package github.javaguide.executor;
/** Immutable snapshot. In shared mode pool gauges repeat per service; counters remain per service. */
public final class ExecutorMetrics {
    public final int active, queued, queueCapacity;
    public final long completed, rejected, executionNanos;
    public ExecutorMetrics(int active, int queued, int capacity, long completed, long rejected, long executionNanos) {
        this.active=active; this.queued=queued; this.queueCapacity=capacity; this.completed=completed; this.rejected=rejected; this.executionNanos=executionNanos;
    }
}
