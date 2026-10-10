package github.javaguide.executor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Fixed service inventory. AbortPolicy never runs user code on a Netty I/O thread. */
public final class ServiceExecutorManager implements AutoCloseable {
    private static final class Counters { final LongAdder completed=new LongAdder(), rejected=new LongAdder(), nanos=new LongAdder(); }
    private static final class Task implements Runnable {
        final Runnable body, abandoned; final Counters counters;
        Task(Runnable body, Runnable abandoned, Counters counters) { this.body=body; this.abandoned=abandoned; this.counters=counters; }
        @Override public void run() { long start=System.nanoTime(); try { body.run(); } finally { counters.completed.increment(); counters.nanos.add(System.nanoTime()-start); } }
    }
    private final Map<ServiceKey, ThreadPoolExecutor> pools = new LinkedHashMap<>();
    private final Map<ServiceKey, Counters> counters = new HashMap<>();
    private final Map<ServiceKey, Integer> capacities = new HashMap<>();
    private final long waitMillis; private boolean closed, shutdownStarted;
    public ServiceExecutorManager(Collection<ServiceKey> services, RpcExecutorConfig config) {
        waitMillis=config.shutdownMillis; ThreadPoolExecutor shared=null;
        if (config.mode==RpcExecutorConfig.Mode.SHARED) shared=pool(config.shared,"shared");
        int index=0;
        for (ServiceKey key: services) {
            if (pools.containsKey(key)) throw new IllegalArgumentException("Duplicate service: "+key);
            RpcExecutorConfig.Limits limits=config.mode==RpcExecutorConfig.Mode.SHARED?config.shared:config.limits(key);
            pools.put(key,shared!=null?shared:pool(limits,"service-"+(index++)));
            counters.put(key,new Counters()); capacities.put(key,limits.queue);
        }
        if (services.isEmpty() && shared!=null) shared.shutdown();
    }
    private ThreadPoolExecutor pool(RpcExecutorConfig.Limits limits, String label) {
        AtomicInteger ids=new AtomicInteger();
        return new ThreadPoolExecutor(limits.threads,limits.threads,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(limits.queue),
            r -> new Thread(r,"rpc-business-"+label+"-"+ids.incrementAndGet()),new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized void execute(ServiceKey key, Runnable body, Runnable abandoned) {
        Counters c=counters.get(key);
        if (c==null) throw new IllegalArgumentException("Unknown service: "+key);
        if (closed) { c.rejected.increment(); throw new RejectedExecutionException("Server shutting down"); }
        try { pools.get(key).execute(new Task(body,abandoned,c)); }
        catch (RejectedExecutionException e) { c.rejected.increment(); throw e; }
    }
    public synchronized Map<ServiceKey, ExecutorMetrics> metrics() {
        Map<ServiceKey, ExecutorMetrics> result=new LinkedHashMap<>();
        pools.forEach((key,p) -> { Counters c=counters.get(key); result.put(key,new ExecutorMetrics(p.getActiveCount(),p.getQueue().size(),capacities.get(key),c.completed.sum(),c.rejected.sum(),c.nanos.sum())); });
        return Collections.unmodifiableMap(result);
    }
    public synchronized boolean isClosed() { return closed; }
    public synchronized void stopAccepting() { closed=true; }
    @Override public void close() {
        Set<ThreadPoolExecutor> unique;
        synchronized(this) { if(shutdownStarted)return; shutdownStarted=true; closed=true; unique=new HashSet<>(pools.values()); unique.forEach(ThreadPoolExecutor::shutdown); }
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
        for(ThreadPoolExecutor p:unique) {
            try { if(p.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))continue; }
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            for(Runnable queued:p.shutdownNow())if(queued instanceof Task)((Task)queued).abandoned.run();
        }
        for(ThreadPoolExecutor p:unique) {
            try { if(!p.awaitTermination(1000,TimeUnit.MILLISECONDS))throw new IllegalStateException("Business task ignored shutdown interrupt"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Shutdown interrupted",e);}
        }
    }
}
