package local.bbsgeoimporter;

import java.util.*;

/** Resource addresses are independent of loader ownership. */
public final class ResourceEvidence {
    public record Observation(long generation, String model, String texture, String animation, String adapter) {
        public Observation {
            model = address(model);
            texture = texture == null ? null : address(texture);
            animation = animation == null ? null : address(animation);
            Objects.requireNonNull(adapter);
            if (texture == null && animation == null) throw new IllegalArgumentException("Empty observation");
        }
    }
    public record Snapshot(long generation, String address, String supplier, byte[] content) {
        public Snapshot {
            address = ResourceEvidence.address(address);
            Objects.requireNonNull(supplier);
            content = content.clone();
        }
        @Override public byte[] content() { return content.clone(); }
    }
    public static String address(String value) {
        Objects.requireNonNull(value);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid resource address: " + value);
        String path = value.substring(value.indexOf(':') + 1);
        if (path.startsWith("/") || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("Unsafe resource path: " + value);
        return value;
    }
    public static String address(ResourceCatalog.Entry entry) {
        String path = entry.path();
        if (!path.startsWith("assets/")) return null;
        int slash = path.indexOf('/', 7);
        if (slash < 0) return null;
        try { return address(path.substring(7, slash) + ":" + path.substring(slash + 1)); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private ResourceEvidence() {}
}
