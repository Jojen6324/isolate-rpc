package github.javaguide.benchmark.scenario;
import github.javaguide.exception.RpcCallException;
import java.util.concurrent.*;
/** Classification shared by scenario reporting. Unrecognized exceptions remain correctness failures. */
public final class FaultController {
    public static String classify(Throwable error){
        if(error==null)return "success";
        while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();
        if(error instanceof RpcCallException)return ((RpcCallException)error).getKind().name().toLowerCase(java.util.Locale.ROOT);
        if(error instanceof CancellationException)return "cancelled";
        return "correctness_error";
    }
}
