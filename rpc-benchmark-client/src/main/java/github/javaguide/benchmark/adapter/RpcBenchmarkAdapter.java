package github.javaguide.benchmark.adapter;
import github.javaguide.benchmark.api.*;
import java.util.concurrent.CompletableFuture;
public interface RpcBenchmarkAdapter extends AutoCloseable {
    EchoResponse invoke(String group,EchoRequest request);
    CompletableFuture<EchoResponse> invokeAsync(String group,EchoRequest request);
    int pendingCount(); int timerCount(); int connectionCount();
    void close();
}
