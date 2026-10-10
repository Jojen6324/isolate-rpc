package github.javaguide.benchmark.server;
import github.javaguide.benchmark.api.*;
import java.util.concurrent.CountDownLatch;
/** Controlled fault fixture; excluded from the two-service performance resource budget. */
public class FaultService implements BenchmarkService {
    public final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    @Override public EchoResponse echo(EchoRequest r){
        if("null".equals(r.behavior))return null;
        if("error".equals(r.behavior))throw new IllegalArgumentException("Injected business failure");
        if("block".equals(r.behavior)) {entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
        return new EchoResponse(r.id,r.payload);
    }
}
