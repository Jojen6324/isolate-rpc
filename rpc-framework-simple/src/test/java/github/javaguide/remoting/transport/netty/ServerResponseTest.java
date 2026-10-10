package github.javaguide.remoting.transport.netty;
import github.javaguide.remoting.transport.netty.server.NettyRpcServerHandler;
import github.javaguide.remoting.dto.*;
import github.javaguide.remoting.constants.RpcConstants;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServerResponseTest {
    @Test void nullAndBusinessFailureKeepRequestIdAndConnection() {
        EmbeddedChannel a = new EmbeddedChannel(new NettyRpcServerHandler(r -> {
            if (r.getMethodName().equals("fail")) throw new IllegalArgumentException(); return null;
        }));
        try {
            for (String method : new String[]{"null", "fail", "null"}) {
                RpcRequest rpc = RpcRequest.builder().requestId(method).methodName(method).build();
                a.writeInbound(RpcMessage.builder().messageType(RpcConstants.REQUEST_TYPE).data(rpc).build());
                RpcResponse<?> result = (RpcResponse<?>) ((RpcMessage)a.readOutbound()).getData();
                assertEquals(method, result.getRequestId()); assertNull(result.getData());
                assertEquals(method.equals("fail") ? 501 : 200, result.getCode().intValue()); assertTrue(a.isActive());
            }
        } finally { a.finishAndReleaseAll(); }
    }
    @Test void twoChannelsUseIndependentHandlers() {
        EmbeddedChannel a = new EmbeddedChannel(new NettyRpcServerHandler(r -> "a"));
        EmbeddedChannel b = new EmbeddedChannel(new NettyRpcServerHandler(r -> "b"));
        assertTrue(a.isActive()); assertTrue(b.isActive()); a.finishAndReleaseAll(); b.finishAndReleaseAll();
    }
}
