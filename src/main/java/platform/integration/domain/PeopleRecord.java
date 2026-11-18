package platform.integration.domain;
import jakarta.json.JsonObject;
import java.util.UUID;
public record PeopleRecord(JsonObject person,UUID identity) { }
