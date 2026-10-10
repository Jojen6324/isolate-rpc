package github.javaguide.benchmark.server;
import github.javaguide.benchmark.api.*;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.executor.*;
import github.javaguide.remoting.transport.netty.server.NettyRpcServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
public final class BenchmarkServer {
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("config metrics.csv host port");
        Properties p=new Properties();try(InputStream in=Files.newInputStream(Paths.get(args[0]))){p.load(in);}
        RpcExecutorConfig config=new RpcExecutorConfig(RpcExecutorConfig.Mode.valueOf(p.getProperty("mode")),
            number(p,"shared.threads"),number(p,"shared.queue"),number(p,"isolated.threads"),number(p,"isolated.queue"),number(p,"shutdown.millis"));
        boolean swap=Boolean.parseBoolean(p.getProperty("swap.roles","false"));
        BenchmarkService fast=swap?new SlowService(number(p,"slow.delay.millis")):new FastService();
        BenchmarkService slow=swap?new FastService():new SlowService(number(p,"slow.delay.millis"));
        List<RpcServiceConfig> services=Arrays.asList(service("fast",fast),service("slow",slow));
        for(String group:Arrays.asList("fast","slow"))config=config.withService(new ServiceKey(BenchmarkService.class.getName(),group,"v1"),
            Integer.parseInt(p.getProperty("service."+group+".threads",p.getProperty("isolated.threads"))),
            Integer.parseInt(p.getProperty("service."+group+".queue",p.getProperty("isolated.queue"))));
        try(NettyRpcServer server=new NettyRpcServer(services,new InetSocketAddress(args[2],Integer.parseInt(args[3])),config);
            PrintWriter out=new PrintWriter(Files.newBufferedWriter(Paths.get(args[1])))){
            InetSocketAddress bound=server.startAsync();
            out.println("elapsed_ns,service,active,queued,queue_capacity,completed,rejected,execution_ns,cpu_ns,heap_used,gc_count,gc_millis");
            long start=System.nanoTime();ScheduledExecutorService metrics=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"rpc-metrics"));
            metrics.scheduleAtFixedRate(()->{
                long gc=0,gcMillis=0;for(GarbageCollectorMXBean bean:ManagementFactory.getGarbageCollectorMXBeans()){gc+=Math.max(0,bean.getCollectionCount());gcMillis+=Math.max(0,bean.getCollectionTime());}
                long heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                long cpu=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
                for(Map.Entry<ServiceKey,ExecutorMetrics> e:server.metrics().entrySet()){
                    ExecutorMetrics m=e.getValue();out.printf(Locale.ROOT,"%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",System.nanoTime()-start,e.getKey().getGroup(),m.active,m.queued,m.queueCapacity,m.completed,m.rejected,m.executionNanos,cpu,heap,gc,gcMillis);
                }out.flush();
            },0,1,TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(()->{metrics.shutdownNow();try{metrics.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(out){out.flush();}},"rpc-metrics-shutdown"));
            System.out.println("READY "+bound+" "+config.mode);
            new CountDownLatch(1).await();
        }
    }
    private static int number(Properties p,String key){return Integer.parseInt(p.getProperty(key));}
    private static RpcServiceConfig service(String group,BenchmarkService impl){return RpcServiceConfig.builder().group(group).version("v1").service(impl).build();}
}
