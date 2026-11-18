package platform.integration.integration.disasters;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.DisasterPort;
import platform.integration.integration.http.IntegrationBudget;
import platform.integration.integration.http.BoundedTransport;
import java.io.StringReader;
import java.util.UUID;

@ApplicationScoped
public class DisasterAdapter implements DisasterPort {
    private final BoundedTransport transport = new BoundedTransport(4, 4 * 1024 * 1024);
    @Inject IntegrationBudget budget;
    @Inject platform.integration.integration.http.ClientCredentials credentials;
    @PreDestroy void close() { transport.close(); }

    private BoundedTransport.Result call(String path, String body) {
        String token = credentials.token(body == null ? "DISASTERS_READ_OAUTH" : "DISASTERS_REPORT_OAUTH", transport, budget.remainingMillis());
        String base;
        try { base = BoundedTransport.base(System.getenv("DISASTERS_BASE_URL"), false); }
        catch (IllegalArgumentException e) { throw new WebApplicationException(503); }
        if (token == null || token.isBlank()) throw new WebApplicationException(503);
        try { return transport.request(base, path, "Bearer " + token, body, budget.remainingMillis()); }
        catch (BoundedTransport.Failure e) { throw new WebApplicationException(e.status()); }
    }

    public JsonObject requireOrganization(int disasterId, String organization) {
        var result = call("/api/desastres/" + disasterId, null);
        if (result.status() == 404) throw new WebApplicationException(404);
        if (result.status() != 200) throw failure(result.status());
        JsonObject owner = object(result.body());
        try {
            if (owner.getInt("id") != disasterId) throw new WebApplicationException(502);
            var associations = owner.getJsonArray("organizations");
            if (associations == null) throw new WebApplicationException(502);
            boolean member = owner.get("registeringOrganizationId") instanceof JsonString value && organization.equals(value.getString());
            for (var entry : associations) {
                if (!(entry instanceof JsonObject association)) throw new WebApplicationException(502);
                if (organization.equals(association.getString("organizationId"))) member = true;
            }
            if (!member) throw new WebApplicationException(403);
            return owner;
        } catch (WebApplicationException e) { throw e; }
          catch (RuntimeException e) { throw new WebApplicationException(502); }
    }

    public ReportReceipt receiveReport(JsonObject command) {
        var result = call("/api/reportes-desastre", command.toString());
        if (result.status() == 400 || result.status() == 409 || result.status() == 422) throw new WebApplicationException(result.status());
        if (result.status() != 200 && result.status() != 201) throw failure(result.status());
        var receipt = object(result.body());
        try {
            UUID id = UUID.fromString(receipt.getString("id"));
            if (!command.getString("sourceId").equals(receipt.getString("sourceId")) ||
                !command.getString("sourceReportId").equals(receipt.getString("sourceReportId")) ||
                !command.getString("registeringOrganizationId").equals(receipt.getString("registeringOrganizationId")))
                throw new WebApplicationException(502);
            var view = Json.createObjectBuilder().add("id", id.toString()).add("sourceReportId", receipt.getString("sourceReportId"));
            var disaster = receipt.get("disasterId");
            if (disaster == null || disaster == JsonValue.NULL) view.addNull("disasterId");
            else if (disaster instanceof JsonNumber number && number.intValueExact() > 0)
                view.add("disasterId", number.intValueExact());
            else throw new WebApplicationException(502);
            return new ReportReceipt(result.status(), view.build());
        } catch (WebApplicationException e) { throw e; }
          catch (RuntimeException e) { throw new WebApplicationException(502); }
    }

    private static JsonObject object(String body) {
        try (var reader = Json.createReader(new StringReader(body))) { return reader.readObject(); }
        catch (RuntimeException e) { throw new WebApplicationException(502); }
    }
    private static WebApplicationException failure(int status) {
        return new WebApplicationException(status == 401 || status == 403 || status >= 500 || status == 429 ? 503 : 502);
    }
}
