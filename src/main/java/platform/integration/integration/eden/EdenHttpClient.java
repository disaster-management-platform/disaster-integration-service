package platform.integration.integration.eden;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.integration.http.BoundedTransport;
import platform.integration.integration.http.IntegrationBudget;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
public class EdenHttpClient {
    @Inject IntegrationBudget budget;
    private BoundedTransport transport;
    private String authorizationHeader;
    private String baseUrl;

    @PostConstruct void start() {
        baseUrl = BoundedTransport.base(setting("EDEN_BASE_URL"), "true".equals(System.getenv("EDEN_ALLOW_DEVELOPMENT_HTTP")));
        authorizationHeader = "Basic " + Base64.getEncoder().encodeToString((setting("EDEN_TECH_USER") + ":" + setting("EDEN_TECH_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        transport = new BoundedTransport(8, 4 * 1024 * 1024);
    }
    @PreDestroy void stop() { if (transport != null) transport.close(); }

    public String nativeUrl(String path) {
        String value = System.getenv("EDEN_PUBLIC_BASE_URL");
        if (value == null || value.isBlank()) return null;
        try { return BoundedTransport.base(value, false) + path; }
        catch (IllegalArgumentException invalid) { return null; }
    }
    public String get(String path) { return call(path, authorizationHeader, null, false); }
    public String delegated(String path, String authorization, String body) { return call(path, authorization, body, true); }
    private String call(String path, String authorization, String body, boolean delegated) {
        try {
            var result = transport.request(baseUrl, path, authorization, body, budget.remainingMillis());
            int status = result.status();
            if (status >= 200 && status < 300) return result.body();
            if (delegated && status == 403 && path.matches(".*/[1-9][0-9]*\\.s3json(?:\\?.*)?")) throw new WebApplicationException(404);
            if (status == 401 || status == 403 || status >= 500 || status == 429) throw new WebApplicationException(503);
            if (status == 404) throw new WebApplicationException(404);
            if (body != null && (status == 400 || status == 422 || status == 409)) throw new WebApplicationException(status == 409 ? 409 : 422);
            throw new WebApplicationException(502);
        } catch (BoundedTransport.Failure failure) { throw new WebApplicationException(failure.status()); }
    }
    public boolean probe() {
        try (var reader = Json.createReader(new StringReader(get("/org/organisation.json?start=0&limit=1")))) {
            var array = reader.readArray();
            return array.size() <= 1 && (array.isEmpty() || OrganizationAdapter.validIdentity(array.getJsonObject(0)));
        } catch (RuntimeException failure) { return false; }
    }
    private static String setting(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required environment setting: " + key);
        return value;
    }
}
