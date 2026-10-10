package github.javaguide.benchmark.scenario;
import github.javaguide.benchmark.adapter.GuideRpcAdapter;
import github.javaguide.benchmark.api.*;
import github.javaguide.serialize.hessian.HessianSerializer;
import github.javaguide.compress.gzip.GzipCompress;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.management.*;
import java.util.concurrent.locks.LockSupport;

/** Open-loop schedules per service with a bounded in-flight budget and separate bounded result writer. */
public final class ScenarioRunner {
    private static final class Row {
        String service,phase,id,status; long planned,actual,completed,latency,lag; int inflight,pending;
        String csv(){return String.format(Locale.ROOT,"%s,%s,%s,%d,%d,%d,%s,%d,%d,%d,%d",service,phase,id,planned,actual,completed,status,latency,lag,inflight,pending);}
    }
    public static void main(String[] args)throws Exception{
        Map<String,String> options=new HashMap<>();for(int i=0;i<args.length;i+=2){if(i+1>=args.length)throw new IllegalArgumentException("key value pairs required");options.put(args[i],args[i+1]);}
        String output=options.getOrDefault("--output","results/scenario");Files.createDirectories(Paths.get(output));
        int connections=integer(options,"--connections",1),max=integer(options,"--max-inflight",512),payload=integer(options,"--payload",128);
        int[] seconds={integer(options,"--warmup-seconds",30),integer(options,"--healthy-seconds",60),integer(options,"--overload-seconds",120),integer(options,"--recovery-seconds",60)};
        if(max<1||payload<0||Arrays.stream(seconds).anyMatch(n->n<0)||Arrays.stream(seconds).sum()==0)throw new IllegalArgumentException("Invalid workload limits");
        double fastRate=number(options,"--fast-rate",100),slowRate=number(options,"--slow-rate",120),healthySlowRate=number(options,"--healthy-slow-rate",20);
        if(!Double.isFinite(fastRate)||!Double.isFinite(slowRate)||!Double.isFinite(healthySlowRate)||fastRate<=0||slowRate<0||healthySlowRate<0)throw new IllegalArgumentException("Invalid rates");
        long timeout=integer(options,"--timeout",3000);boolean swap=Boolean.parseBoolean(options.getOrDefault("--swap-roles","false"));
        ArrayBlockingQueue<Row> rows=new ArrayBlockingQueue<>(65536);AtomicBoolean writingDone=new AtomicBoolean();AtomicLong dropped=new AtomicLong();
        AtomicReference<Throwable> writerError=new AtomicReference<>();
        Thread writer=new Thread(()->{try(PrintWriter out=new PrintWriter(Files.newBufferedWriter(Paths.get(output,"scenario.csv")))){
            out.println("service,phase,request_id,planned_ns,actual_ns,completed_ns,status,latency_ns,scheduling_ns,inflight,pending");
            while(!writingDone.get()||!rows.isEmpty()){Row row=rows.poll(100,TimeUnit.MILLISECONDS);if(row!=null)out.println(row.csv());}
            if(out.checkError())throw new IOException("Result writer failed");
        }catch(Exception e){writerError.set(e);}},"rpc-result-writer");writer.start();
        Semaphore permits=new Semaphore(max);AtomicInteger inflight=new AtomicInteger();AtomicLong scheduled=new AtomicLong();
        byte[] bytes=new byte[payload];new Random(6324L).nextBytes(bytes);
        Map<String,Object> stats=new LinkedHashMap<>();stats.put("options",options);stats.put("phase_seconds",seconds);stats.put("seed",6324);
        stats.put("configured_connections",connections);stats.put("max_inflight",max);stats.put("payload_bytes",payload);stats.put("timeout_millis",timeout);
        byte[] serialized=new HessianSerializer().serialize(GuideRpcAdapter.rpcRequest("fast",new EchoRequest("wire-size-sample",bytes)));
        stats.put("serialized_request_bytes",serialized.length);stats.put("compressed_request_bytes",new GzipCompress().compress(serialized).length);
        stats.put("wire_header_bytes",16);stats.put("client_jvm_args",ManagementFactory.getRuntimeMXBean().getInputArguments());
        long epoch=System.nanoTime();long wallStart=System.currentTimeMillis();stats.put("wall_start_millis",wallStart);
        try(GuideRpcAdapter adapter=new GuideRpcAdapter(options.getOrDefault("--host","127.0.0.1"),integer(options,"--port",9998),connections,timeout)){
            adapter.invoke(swap?"slow":"fast",new EchoRequest("setup",bytes));stats.put("actual_connections",adapter.connectionCount());
            final long start=System.nanoTime();stats.put("schedule_start_offset_ns",start-epoch);
            PrintWriter clientMetrics=new PrintWriter(Files.newBufferedWriter(Paths.get(output,"client-metrics.csv")));
            clientMetrics.println("elapsed_ns,cpu_ns,heap_used,gc_count,gc_millis,inflight,pending,timers");
            ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"rpc-client-metrics"));
            sampler.scheduleAtFixedRate(()->{
                long gc=0,gcMillis=0;for(GarbageCollectorMXBean bean:ManagementFactory.getGarbageCollectorMXBeans()){gc+=Math.max(0,bean.getCollectionCount());gcMillis+=Math.max(0,bean.getCollectionTime());}
                clientMetrics.printf(Locale.ROOT,"%d,%d,%d,%d,%d,%d,%d,%d%n",System.nanoTime()-start,
                    ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(),
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),gc,gcMillis,inflight.get(),adapter.pendingCount(),adapter.timerCount());clientMetrics.flush();
            },0,1,TimeUnit.SECONDS);
            try {
            Thread[] schedulers=new Thread[2];String[] labels={"fast","slow"},phases={"warmup","healthy","overload","recovery"};
            for(int s=0;s<2;s++){
                final String label=labels[s];final String group=swap?(label.equals("fast")?"slow":"fast"):label;
                schedulers[s]=new Thread(()->{
                    long phaseStart=start;
                    for(int phase=0;phase<seconds.length;phase++){
                        long phaseEnd=phaseStart+TimeUnit.SECONDS.toNanos(seconds[phase]);
                        double rate=label.equals("fast")?fastRate:(phase==2?slowRate:healthySlowRate);
                        if(rate>0){
                            long count=(long)Math.ceil(seconds[phase]*rate);
                            for(long n=0;n<count;n++){
                                long planned=phaseStart+(long)(n*1_000_000_000.0/rate);if(planned>=phaseEnd)break;
                                while(System.nanoTime()<planned)LockSupport.parkNanos(Math.min(1000000,planned-System.nanoTime()));
                                Row row=new Row();row.service=label;row.phase=phases[phase];row.id=label+"-"+scheduled.incrementAndGet();row.planned=planned-start;
                                row.lag=Math.max(0,System.nanoTime()-planned);
                                if(!permits.tryAcquire()){
                                    row.status="client_not_sent";row.completed=System.nanoTime()-start;row.inflight=inflight.get();row.pending=adapter.pendingCount();
                                    if(!rows.offer(row))dropped.incrementAndGet();continue;
                                }
                                row.inflight=inflight.incrementAndGet();row.actual=System.nanoTime()-start;
                                try {
                                    adapter.invokeAsync(group,new EchoRequest(row.id,bytes)).whenComplete((response,error)->{
                                        row.completed=System.nanoTime()-start;row.latency=row.completed-row.actual;row.status=FaultController.classify(error);
                                        row.pending=adapter.pendingCount();inflight.decrementAndGet();if(!rows.offer(row))dropped.incrementAndGet();permits.release();
                                    });
                                } catch(RuntimeException e) {
                                    row.completed=System.nanoTime()-start;row.latency=row.completed-row.actual;row.status=FaultController.classify(e);row.pending=adapter.pendingCount();
                                    inflight.decrementAndGet();if(!rows.offer(row))dropped.incrementAndGet();permits.release();
                                }
                            }
                        }phaseStart=phaseEnd;
                    }
                },"rpc-arrivals-"+label);schedulers[s].start();
            }
            for(Thread scheduler:schedulers)scheduler.join();
            // Complete the last scheduled phase window before taking the final drain snapshot.
            long end=start+TimeUnit.SECONDS.toNanos(Arrays.stream(seconds).sum());while(System.nanoTime()<end)LockSupport.parkNanos(Math.min(1000000,end-System.nanoTime()));
            boolean drained=permits.tryAcquire(max,timeout+5000,TimeUnit.MILLISECONDS);if(!drained)throw new IllegalStateException("Inflight drain exceeded deadline");
            stats.put("pending_end",adapter.pendingCount());stats.put("timer_tasks_end",adapter.timerCount());stats.put("late_responses",adapter.lateResponses());
            stats.put("scheduled",scheduled.get());stats.put("duration_ns",System.nanoTime()-start);
            if(adapter.pendingCount()!=0||adapter.timerCount()!=0)throw new IllegalStateException("Pending resources leaked");
            } finally {sampler.shutdownNow();sampler.awaitTermination(2,TimeUnit.SECONDS);clientMetrics.close();}
        }finally{writingDone.set(true);writer.join(5000);if(writer.isAlive())throw new IllegalStateException("Writer did not stop");}
        stats.put("writer_dropped",dropped.get());stats.put("client_cpu_ns",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime());
        stats.put("client_heap_used",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        stats.put("client_project_threads_alive",Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&(t.getName().startsWith("rpc-")||t.getName().startsWith("nioEventLoopGroup-"))).count());
        Files.write(Paths.get(output,"client-statistics.json"),new GsonBuilder().setPrettyPrinting().create().toJson(stats).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(dropped.get()!=0||writerError.get()!=null)throw new IllegalStateException("Result output incomplete",writerError.get());
        System.out.println("SCENARIO_COMPLETE scheduled="+scheduled.get()+" pending=0 timers=0");
    }
    private static int integer(Map<String,String> o,String key,int value){return Integer.parseInt(o.getOrDefault(key,Integer.toString(value)));}
    private static double number(Map<String,String> o,String key,double value){return Double.parseDouble(o.getOrDefault(key,Double.toString(value)));}
}
