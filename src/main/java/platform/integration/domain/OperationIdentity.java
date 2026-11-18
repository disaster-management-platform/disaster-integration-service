package platform.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class OperationIdentity {
    private OperationIdentity() { }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String scoped(String... parts) {
        var text = new StringBuilder();
        for (String part : parts) text.append(part.length()).append(':').append(part);
        return digest(text.toString());
    }
    public static UUID key(String... parts) {
        return UUID.nameUUIDFromBytes(scoped(parts).getBytes(StandardCharsets.UTF_8));
    }
}
