package github.javaguide.remoting.transport.netty.server;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.registry.zk.util.CuratorUtils;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.transport.netty.codec.*;
import github.javaguide.executor.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.*;

/** One dispatch path for both executor modes. Live service inventory is fixed at startup. */
@Component
public class NettyRpcServer implements AutoCloseable {
    public static final int PORT=9998;
    private final boolean registryBacked;
    private final Collection<RpcServiceConfig> localServices;
    private final InetSocketAddress listen;
    private final RpcExecutorConfig config;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Map<ServiceKey,Object> services=new HashMap<>();
    private ServiceExecutorManager executors;
    private EventLoopGroup boss,worker;
    private Channel listener;
    private Thread hook;
    private boolean started;
    public NettyRpcServer() { this(null,defaultAddress(),RpcExecutorConfig.defaults()); }
    public NettyRpcServer(Collection<RpcServiceConfig> services, InetSocketAddress listen, RpcExecutorConfig config) {
        this.registryBacked=services==null; this.localServices=services==null?null:new ArrayList<>(services); this.listen=listen; this.config=config;
    }
    private static InetSocketAddress defaultAddress() {
        try{return new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(),PORT);}
        catch(UnknownHostException e){throw new IllegalStateException(e);}
    }
    public synchronized void registerService(RpcServiceConfig service) {
        if(started||closed.get())throw new IllegalStateException("Service inventory already fixed");
        if(registryBacked)SingletonFactory.getInstance(ZkServiceProviderImpl.class).publishService(service);
        else localServices.add(service);
    }
    public synchronized InetSocketAddress startAsync() {
        if(started||closed.get())throw new IllegalStateException("Server cannot start twice"); started=true;
        Collection<RpcServiceConfig> inventory=registryBacked?SingletonFactory.getInstance(ZkServiceProviderImpl.class).getServices():localServices;
        for(RpcServiceConfig c:inventory) if(services.put(ServiceKey.of(c),c.getService())!=null)throw new IllegalArgumentException("Duplicate service");
        executors=new ServiceExecutorManager(services.keySet(),config);
        boss=new NioEventLoopGroup(1); worker=new NioEventLoopGroup(2);
        try {
            listener=new ServerBootstrap().group(boss,worker).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY,true).childOption(ChannelOption.SO_KEEPALIVE,true)
                .childHandler(new ChannelInitializer<SocketChannel>(){
                    @Override protected void initChannel(SocketChannel ch){ch.pipeline().addLast(new IdleStateHandler(30,0,0,TimeUnit.SECONDS),
                        new RpcMessageFrameDecoder(),new RpcMessageCodec(),new NettyRpcServerHandler(NettyRpcServer.this::invoke,executors));}
                }).bind(listen).syncUninterruptibly().channel();
            hook=new Thread(this::close,"rpc-server-shutdown"); Runtime.getRuntime().addShutdownHook(hook);
            return (InetSocketAddress)listener.localAddress();
        } catch(RuntimeException e){close();throw e;}
    }
    private Object invoke(RpcRequest request) {
        Object service=services.get(ServiceKey.of(request));
        if(service==null)throw new IllegalArgumentException("Unknown service");
        try {return service.getClass().getMethod(request.getMethodName(),request.getParamTypes()).invoke(service,request.getParameters());}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Business invocation failed",e);}
    }
    public void start(){startAsync();try{listener.closeFuture().syncUninterruptibly();}finally{close();}}
    public Map<ServiceKey,ExecutorMetrics> metrics(){return executors.metrics();}
    @Override public synchronized void close(){
        if(!closed.compareAndSet(false,true))return;
        if(executors!=null)executors.stopAccepting();
        if(listener!=null)listener.close().syncUninterruptibly();
        try {if(executors!=null)executors.close();}
        finally {
            if(worker!=null)worker.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
            if(boss!=null)boss.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
            if(registryBacked && listener!=null)CuratorUtils.clearRegistry(CuratorUtils.getZkClient(),(InetSocketAddress)listener.localAddress());
            if(hook!=null && Thread.currentThread()!=hook)try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}
        }
    }
}
