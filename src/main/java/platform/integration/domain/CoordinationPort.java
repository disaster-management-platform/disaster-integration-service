package platform.integration.domain;

import jakarta.json.JsonObject;

public interface CoordinationPort {
    JsonObject tasks(String organization, int disaster, String eventId, int page, int size);
    JsonObject activities(String organization, int disaster, String eventId, int page, int size);
}
