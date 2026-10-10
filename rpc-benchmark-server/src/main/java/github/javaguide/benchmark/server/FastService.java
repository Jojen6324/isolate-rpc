package github.javaguide.benchmark.server;
import github.javaguide.benchmark.api.*;
public class FastService implements BenchmarkService {
    @Override public EchoResponse echo(EchoRequest request){return new EchoResponse(request.id,request.payload);}
}
