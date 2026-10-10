package github.javaguide.remoting.transport.netty.client;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.exception.RpcCallException;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/** A fresh JVM avoids the existing test suite's static connected ZooKeeper client. */
public class DefaultDiscoveryShutdownTest {
    @Test void unavailableRealRegistryHonorsTimeoutAndClientShutdown() throws Exception {
        int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}
        Path folder=Files.createTempDirectory("rpc-registry-fault-");
        Path properties=folder.resolve("rpc.properties"),output=folder.resolve("probe.log");
        Files.write(properties,Collections.singletonList("rpc.zookeeper.address=127.0.0.1:"+port));
        String javaCommand=System.getProperty("java.home")+"/bin/java";
        String classpath=folder+File.pathSeparator+System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process child=new ProcessBuilder(javaCommand,"-XX:ActiveProcessorCount=4","-Dorg.slf4j.simpleLogger.defaultLogLevel=error","-cp",classpath,DefaultDiscoveryShutdownTest.class.getName())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(8,TimeUnit.SECONDS),"Missing registry must not leave discovery threads alive");
            String log=new String(Files.readAllBytes(output),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,child.exitValue(),log);assertTrue(log.contains("REGISTRY_SHUTDOWN_PASS"),log);
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}Files.deleteIfExists(properties);Files.deleteIfExists(output);Files.deleteIfExists(folder);}
    }
    public static void main(String[] args)throws Exception {
        long started=System.nanoTime();
        try(NettyRpcClient client=new NettyRpcClient()) {
            RpcRequest request=RpcRequest.builder().requestId(UUID.randomUUID().toString()).interfaceName("fault.Interface")
                .group("test").version("v1").methodName("call").parameters(new Object[0]).paramTypes(new Class<?>[0]).build();
            try{client.sendRpcRequestAsync(request,500).get(2,TimeUnit.SECONDS);throw new AssertionError("Unexpected success");}
            catch(ExecutionException e){if(!(e.getCause() instanceof RpcCallException)||((RpcCallException)e.getCause()).getKind()!=RpcCallException.Kind.TIMEOUT)throw e;}
            if(client.pendingCount()!=0||client.timeoutTaskCount()!=0)throw new AssertionError("Resource residue");
        }
        if(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>3000)throw new AssertionError("Registry shutdown exceeded bound");
        if(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&(t.getName().startsWith("rpc-discovery-")||t.getName().startsWith("rpc-timeout-"))))throw new AssertionError("Project threads still running");
        System.out.println("REGISTRY_SHUTDOWN_PASS");
    }
}
