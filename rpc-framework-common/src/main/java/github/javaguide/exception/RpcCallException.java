package github.javaguide.exception;
/** Stable local failure categories; cancellation remains CompletableFuture cancellation. */
public class RpcCallException extends RuntimeException {
    public enum Kind { TIMEOUT, OVERLOADED, TRANSPORT, BUSINESS, CLOSED }
    private final Kind kind;
    public RpcCallException(Kind kind, String message) { this(kind, message, null); }
    public RpcCallException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind getKind() { return kind; }
}
