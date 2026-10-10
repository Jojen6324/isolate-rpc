package github.javaguide.benchmark.api;
import java.io.Serializable;
public class EchoResponse implements Serializable {
    private static final long serialVersionUID=1L;
    public String id; public byte[] payload;
    public EchoResponse(){} public EchoResponse(String id,byte[] payload){this.id=id;this.payload=payload;}
}
