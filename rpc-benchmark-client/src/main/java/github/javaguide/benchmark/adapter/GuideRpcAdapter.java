package github.javaguide.benchmark.adapter;
import github.javaguide.benchmark.api.*;
import github.javaguide.remoting.dto.*;
import github.javaguide.remoting.transport.netty.client.NettyRpcClient;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Uses actual Netty/Hessian/GZIP wire calls; explicit endpoint excludes registry lookup from steady-state timing. */
public final class GuideRpcAdapter implements RpcBenchmarkAdapter {
    private final NettyRpcClient[] clients; private final AtomicInteger next=new AtomicInteger(); private final long timeout;
    public GuideRpcAdapter(String host,int port,int connections,long timeout){
        if(connections<1||connections>64)throw new IllegalArgumentException("connections must be 1..64");
        this.timeout=timeout;clients=new NettyRpcClient[connections];InetSocketAddress address=new InetSocketAddress(host,port);
        try {for(int i=0;i<clients.length;i++){clients[i]=new NettyRpcClient(r->address);clients[i].connectAsync(address).get(4,TimeUnit.SECONDS);}}
        catch(Exception e){close();throw new IllegalStateException("Benchmark connect failed",e);}
    }
    public static RpcRequest rpcRequest(String group,EchoRequest request){
        return RpcRequest.builder().interfaceName(BenchmarkService.class.getName()).group(group).version("v1")
            .methodName("echo").paramTypes(new Class<?>[]{EchoRequest.class}).parameters(new Object[]{request}).requestId(UUID.randomUUID().toString()).build();
    }
    @Override public CompletableFuture<EchoResponse> invokeAsync(String group,EchoRequest request){
        NettyRpcClient client=clients[Math.floorMod(next.getAndIncrement(),clients.length)];
        return client.sendRpcRequestAsync(rpcRequest(group,request),timeout).thenApply(response->{
            EchoResponse echo=(EchoResponse)response.getData();
            if(echo==null||!request.id.equals(echo.id)||!Arrays.equals(request.payload,echo.payload))throw new IllegalStateException("Response content mismatch");
            return echo;
        });
    }
    @Override public EchoResponse invoke(String group,EchoRequest request){return invokeAsync(group,request).join();}
    @Override public int pendingCount(){int count=0;for(NettyRpcClient c:clients)if(c!=null)count+=c.pendingCount();return count;}
    @Override public int timerCount(){int count=0;for(NettyRpcClient c:clients)if(c!=null)count+=c.timeoutTaskCount();return count;}
    @Override public int connectionCount(){int count=0;for(NettyRpcClient c:clients)if(c!=null)count+=c.connectionCount();return count;}
    public long lateResponses(){long count=0;for(NettyRpcClient c:clients)if(c!=null)count+=c.lateResponseCount();return count;}
    @Override public void close(){for(NettyRpcClient c:clients)if(c!=null)c.close();}
    public static void main(String[] args){
        try(GuideRpcAdapter adapter=new GuideRpcAdapter(args[0],Integer.parseInt(args[1]),1,3000)){
            EchoResponse response=adapter.invoke("fast",new EchoRequest("readiness",new byte[]{1,2,3}));
            if(!"readiness".equals(response.id)||adapter.pendingCount()!=0)throw new IllegalStateException("Functional readiness failed");
            System.out.println("RPC_READY");
        }
    }
}
