package github.javaguide;

import github.javaguide.annotation.RpcScan;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.remoting.transport.netty.client.NettyRpcClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @author shuang.kou
 * @createTime 2020年05月10日 07:25:00
 */
@RpcScan(basePackage = {"github.javaguide"})
public class NettyClientMain {
    public static void main(String[] args) throws InterruptedException {
        NettyRpcClient client = (NettyRpcClient) ExtensionLoader.getExtensionLoader(RpcRequestTransport.class).getExtension("netty");
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext(NettyClientMain.class)) {
            HelloController helloController = (HelloController) applicationContext.getBean("helloController");
            helloController.test();
        } finally { client.close(); }
    }
}
