package github.javaguide.benchmark.api;
import java.io.Serializable;
public class EchoRequest implements Serializable {
    private static final long serialVersionUID=1L;
    public String id; public byte[] payload; public String behavior="normal";
    public EchoRequest(){} public EchoRequest(String id,byte[] payload){this.id=id;this.payload=payload;}
}
