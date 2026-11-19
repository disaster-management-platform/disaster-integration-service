package platform.integration.domain;

import jakarta.json.JsonObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface CoordinationPort {
    JsonObject tasks(String organization, int disaster, String eventId, int page, int size);
    JsonObject activities(String organization, int disaster, String eventId, int page, int size);
    JsonObject write(String organization, String eventId, boolean task, String targetId, CoordinationInput input,
                     UUID operation, BooleanSupplier beginWrite);
    JsonObject get(String organization, int disaster, String eventId, boolean task, String id);
}
