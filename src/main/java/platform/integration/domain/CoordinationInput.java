package platform.integration.domain;

import jakarta.json.*;
import jakarta.ws.rs.BadRequestException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

public record CoordinationInput(int disasterId, String organizationId, String projectId, String name,
    String description, String priorityId, String statusId, String activityId, JsonObject assignee,
    String dueAt, JsonNumber timeEstimated, String locationId, String contactPersonId,
    String startsOn, String endsOn, String comments) {
    public static CoordinationInput parse(JsonObject input, boolean task) {
        var common = Set.of("disasterId", "organizationId", "projectId", "name", "statusId");
        var specific = task ? Set.of("description", "priorityId", "activityId", "assignee", "dueAt", "timeEstimated")
            : Set.of("locationId", "contactPersonId", "startsOn", "endsOn", "startsAt", "endsAt", "comments");
        if (input == null || input.keySet().stream().anyMatch(k -> !common.contains(k) && !specific.contains(k))) throw new BadRequestException();
        try {
            int disaster = input.getJsonNumber("disasterId").intValueExact();
            String name = text(input, "name", task ? 100 : 255);
            String status = id(input, "statusId"), priority = id(input, "priorityId");
            if (disaster <= 0 || name == null || name.isBlank() || status == null || task && priority == null) throw new BadRequestException();
            JsonObject assignee = null;
            if (input.containsKey("assignee") && !input.isNull("assignee")) {
                assignee = input.getJsonObject("assignee");
                if (!assignee.keySet().equals(Set.of("kind", "id")) || id(assignee,"id") == null ||
                    !Set.of("person", "team", "organization").contains(assignee.getString("kind"))) throw new BadRequestException();
            }
            JsonNumber estimate = input.containsKey("timeEstimated") && !input.isNull("timeEstimated") ? input.getJsonNumber("timeEstimated") : null;
            if (estimate != null && (estimate.bigDecimalValue().signum() < 0 || !Double.isFinite(estimate.doubleValue()))) throw new BadRequestException();
            String due = text(input,"dueAt",64);
            if (due != null) due = OffsetDateTime.parse(due).toInstant().toString();
            String starts = day(input,"startsOn","startsAt"), ends = day(input,"endsOn","endsAt");
            if (starts != null && ends != null && LocalDate.parse(ends).isBefore(LocalDate.parse(starts))) throw new BadRequestException();
            return new CoordinationInput(disaster, text(input,"organizationId",200), id(input,"projectId"), name,
                text(input,"description",4000), priority, status, id(input,"activityId"), assignee,
                due, estimate, id(input,"locationId"), id(input,"contactPersonId"), starts, ends, text(input,"comments",4000));
        } catch (BadRequestException e) { throw e; }
          catch (RuntimeException e) { throw new BadRequestException(); }
    }
    private static String day(JsonObject input,String date,String instant) {
        String value = text(input,date,10), timestamp = text(input,instant,64);
        if (timestamp != null) {
            var parsed = OffsetDateTime.parse(timestamp);
            if (!parsed.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) throw new BadRequestException();
            if (value != null && !value.equals(parsed.toLocalDate().toString())) throw new BadRequestException();
            value = parsed.toLocalDate().toString();
        }
        return value == null ? null : LocalDate.parse(value).toString();
    }
    private static String id(JsonObject input,String key) {
        String value = text(input,key,18);
        if (value != null && !value.matches("[1-9][0-9]{0,17}")) throw new BadRequestException();
        return value;
    }
    private static String text(JsonObject input,String key,int max) {
        if (!input.containsKey(key) || input.isNull(key)) return null;
        if (!(input.get(key) instanceof JsonString text) || text.getString().length() > max || text.getString().indexOf(0) >= 0) throw new BadRequestException();
        return text.getString();
    }
}
