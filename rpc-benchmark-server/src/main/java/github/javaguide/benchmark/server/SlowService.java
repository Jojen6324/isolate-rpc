package github.javaguide.benchmark.server;
import github.javaguide.benchmark.api.*;
public class SlowService implements BenchmarkService {
    private final long delayMillis;
    public SlowService(long delayMillis){if(delayMillis<0)throw new IllegalArgumentException();this.delayMillis=delayMillis;}
    @Override public EchoResponse echo(EchoRequest request){
        try{Thread.sleep(delayMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Slow task interrupted",e);}
        return new EchoResponse(request.id,request.payload);
    }
}
