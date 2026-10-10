package github.javaguide.remoting.transport.netty.client;
import github.javaguide.enums.*;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.*;
import github.javaguide.exception.RpcCallException;
import io.netty.channel.*;
import io.netty.handler.timeout.*;
import io.netty.util.ReferenceCountUtil;
import static github.javaguide.exception.RpcCallException.Kind.TRANSPORT;

/** A handler belongs to exactly one client; heartbeats never reconnect or discover on I/O threads. */
public class NettyRpcClientHandler extends ChannelInboundHandlerAdapter {
    private final UnprocessedRequests requests;
    public NettyRpcClientHandler(UnprocessedRequests requests) { this.requests = requests; }
    @Override @SuppressWarnings("unchecked") public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof RpcMessage && ((RpcMessage)msg).getMessageType() == RpcConstants.RESPONSE_TYPE)
                requests.complete((RpcResponse<Object>)((RpcMessage)msg).getData(), ctx.channel());
        } finally { ReferenceCountUtil.release(msg); }
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        requests.failChannel(ctx.channel(), new RpcCallException(TRANSPORT, "Connection closed"));
        super.channelInactive(ctx);
    }
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent && ((IdleStateEvent)event).state() == IdleState.WRITER_IDLE) {
            if (ctx.channel().isActive()) ctx.writeAndFlush(RpcMessage.builder()
                .codec(SerializationTypeEnum.HESSIAN.getCode()).compress(CompressTypeEnum.GZIP.getCode())
                .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE).data(RpcConstants.PING).build())
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } else super.userEventTriggered(ctx, event);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        requests.failChannel(ctx.channel(), new RpcCallException(TRANSPORT, "Transport failure", cause)); ctx.close();
    }
}
