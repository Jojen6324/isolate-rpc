package github.javaguide.remoting.transport.netty.server;

import github.javaguide.enums.*;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.*;
import github.javaguide.remoting.handler.RpcRequestHandler;
import io.netty.channel.*;
import io.netty.handler.timeout.*;
import io.netty.util.ReferenceCountUtil;
import java.util.function.Function;
import java.util.concurrent.RejectedExecutionException;
import github.javaguide.executor.*;

/** Per-channel handler. Business failures terminate a request, not its connection. */
public class NettyRpcServerHandler extends ChannelInboundHandlerAdapter {
    private final Function<RpcRequest, Object> invoke;
    private final ServiceExecutorManager executors;
    public NettyRpcServerHandler() {
        this(SingletonFactory.getInstance(RpcRequestHandler.class)::handle);
    }
    public NettyRpcServerHandler(Function<RpcRequest, Object> invoke) { this(invoke, null); }
    public NettyRpcServerHandler(Function<RpcRequest, Object> invoke, ServiceExecutorManager executors) { this.invoke = invoke; this.executors = executors; }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (!(msg instanceof RpcMessage)) return;
            RpcMessage request = (RpcMessage) msg;
            if (request.getMessageType() == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
                reply(ctx, RpcConstants.HEARTBEAT_RESPONSE_TYPE, RpcConstants.PONG);
            } else if (request.getMessageType() == RpcConstants.REQUEST_TYPE) {
                RpcRequest rpc = (RpcRequest) request.getData();
                Runnable task = () -> {
                    RpcResponse<Object> response;
                    try { response = RpcResponse.success(invoke.apply(rpc), rpc.getRequestId()); }
                    catch (RuntimeException e) { response = RpcResponse.fail(RpcResponseCodeEnum.BUSINESS_ERROR, rpc.getRequestId()); }
                    reply(ctx, RpcConstants.RESPONSE_TYPE, response);
                };
                if (executors == null) task.run(); // compatibility/injected handler path; production always supplies a manager
                else {
                    try { executors.execute(ServiceKey.of(rpc), task,
                        () -> reply(ctx, RpcConstants.RESPONSE_TYPE, RpcResponse.fail(RpcResponseCodeEnum.SHUTTING_DOWN, rpc.getRequestId()))); }
                    catch (RejectedExecutionException e) { reply(ctx, RpcConstants.RESPONSE_TYPE, RpcResponse.fail(
                        executors.isClosed() ? RpcResponseCodeEnum.SHUTTING_DOWN : RpcResponseCodeEnum.OVERLOADED, rpc.getRequestId())); }
                    catch (IllegalArgumentException e) { reply(ctx, RpcConstants.RESPONSE_TYPE, RpcResponse.fail(RpcResponseCodeEnum.BUSINESS_ERROR, rpc.getRequestId())); }
                }
            }
        } finally { ReferenceCountUtil.release(msg); }
    }
    private void reply(ChannelHandlerContext ctx, byte type, Object data) {
        if (!ctx.channel().isActive()) return;
        RpcMessage response = RpcMessage.builder().messageType(type)
            .codec(SerializationTypeEnum.HESSIAN.getCode()).compress(CompressTypeEnum.GZIP.getCode()).data(data).build();
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }
    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent && ((IdleStateEvent) event).state() == IdleState.READER_IDLE) ctx.close();
        else super.userEventTriggered(ctx, event);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
