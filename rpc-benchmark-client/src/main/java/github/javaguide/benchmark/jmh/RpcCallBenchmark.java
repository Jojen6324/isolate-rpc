package github.javaguide.benchmark.jmh;
import github.javaguide.benchmark.api.*;
import github.javaguide.benchmark.adapter.GuideRpcAdapter;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
@BenchmarkMode({Mode.Throughput,Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RpcCallBenchmark {
    @State(Scope.Benchmark)
    public static class ClientState {
        @Param({"127.0.0.1"}) public String host;
        @Param({"9998"}) public int port;
        @Param({"1"}) public int connections;
        @Param({"3000"}) public long timeoutMillis;
        GuideRpcAdapter adapter;
        @Setup(Level.Trial) public void setup(){adapter=new GuideRpcAdapter(host,port,connections,timeoutMillis);adapter.invoke("fast",new EchoRequest("setup",new byte[]{7}));}
        @TearDown(Level.Trial) public void close(){
            try {if(adapter.pendingCount()!=0||adapter.timerCount()!=0)throw new IllegalStateException("Benchmark leaked pending requests");}
            finally {adapter.close();}
        }
    }
    @State(Scope.Thread)
    public static class RequestState {
        @Param({"128","1024"}) public int payload;
        EchoRequest request; long calls;
        @Setup(Level.Trial) public void setup(){byte[] bytes=new byte[payload];new Random(6324L).nextBytes(bytes);request=new EchoRequest("",bytes);}
    }
    @Benchmark public EchoResponse echo(ClientState client,RequestState state){
        state.request.id=Thread.currentThread().getId()+"-"+(++state.calls);
        return client.adapter.invoke("fast",state.request);
    }
}
