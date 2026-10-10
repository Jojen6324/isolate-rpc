package github.javaguide.executor;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.remoting.dto.RpcRequest;
import java.util.Objects;
/** Structured executor identity; ZooKeeper's legacy paths remain unchanged. */
public final class ServiceKey {
    private final String service, group, version;
    public ServiceKey(String service, String group, String version) {
        this.service = Objects.requireNonNull(service); this.group = group == null ? "" : group; this.version = version == null ? "" : version;
    }
    public static ServiceKey of(RpcRequest r) { return new ServiceKey(r.getInterfaceName(), r.getGroup(), r.getVersion()); }
    public static ServiceKey of(RpcServiceConfig s) { return new ServiceKey(s.getServiceName(), s.getGroup(), s.getVersion()); }
    public String getService() { return service; } public String getGroup() { return group; } public String getVersion() { return version; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ServiceKey)) return false; ServiceKey k = (ServiceKey)o;
        return service.equals(k.service) && group.equals(k.group) && version.equals(k.version);
    }
    @Override public int hashCode() { return Objects.hash(service, group, version); }
    @Override public String toString() { return service + "[group=" + group + ",version=" + version + "]"; }
}
