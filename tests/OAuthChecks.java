import platform.integration.integration.http.BoundedTransport;
import platform.integration.integration.http.ClientCredentials;

public final class OAuthChecks {
    public static void main(String[] args) throws Exception {
        try (var transport = new BoundedTransport(2, 65536)) {
            var credentials = new ClientCredentials();
            String first = credentials.token("SECURITY_OAUTH", transport, 3000);
            if (!first.equals(credentials.token("SECURITY_OAUTH", transport, 3000))) throw new AssertionError("Token cache");
            Thread.sleep(6000);
            String renewed = credentials.token("SECURITY_OAUTH", transport, 3000);
            if (first.equals(renewed)) throw new AssertionError("Token renewal");
            if (first.split("\\.").length != 3 || renewed.split("\\.").length != 3) throw new AssertionError("JWT format");
            System.out.println("PASS actual Keycloak Client Credentials cache and expiry renewal");
        }
    }
}
