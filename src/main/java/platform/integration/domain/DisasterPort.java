package platform.integration.domain;

import jakarta.json.JsonObject;

public interface DisasterPort {
    record ReportReceipt(int status, JsonObject body) { }
    JsonObject requireOrganization(int disasterId, String organization);
    ReportReceipt receiveReport(JsonObject command);
}
