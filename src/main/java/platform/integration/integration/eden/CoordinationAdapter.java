package platform.integration.integration.eden;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.CoordinationPort;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** Read the projects explicitly linked to an authorized Eden event, then their native activities/tasks. */
@ApplicationScoped
public class CoordinationAdapter implements CoordinationPort {
    @Inject EdenHttpClient http;
    @Inject EdenDelegations delegations;
    private static final String EXPORT = "show_ids=true&mcomponents=none&maxdepth=0&references=";

    public JsonObject tasks(String organization, int disaster, String eventId, int page, int size) {
        return list(organization, disaster, eventId, page, size, true);
    }
    public JsonObject activities(String organization, int disaster, String eventId, int page, int size) {
        return list(organization, disaster, eventId, page, size, false);
    }
    private JsonObject list(String organization, int disaster, String eventId, int page, int size, boolean tasks) {
        String credential = delegations.coordinationAuthorization(organization);
        String nativeOrg = delegations.nativeOrganization(organization);
        try {
            List<String> linked = linkedProjects(eventId, credential);
            if (linked.isEmpty()) return page(Json.createArrayBuilder(), page, size, false);
            var projects = rows(http.delegated("/project/project.s3json?" + EXPORT + "organisation_id&project.id__belongs=" + String.join(",", linked) + "&start=0&limit=101", credential, null), "project_project", 101);
            if (projects.size() > 100) throw new WebApplicationException(503);
            var owned = new LinkedHashSet<String>();
            for (var project : projects) {
                if (!linked.contains(id(project.getString("@id")))) throw new WebApplicationException(502);
                String org = reference(project, "organisation_id");
                if (org == null) throw new WebApplicationException(502);
                if (nativeOrg.equals(org)) owned.add(id(project.getString("@id")));
            }
            if (owned.isEmpty()) return page(Json.createArrayBuilder(), page, size, false);
            String resource = tasks ? "task" : "activity";
            String refs = tasks ? "project_id,activity_id" : "project_id,status_id,location_id";
            String path = "/project/" + resource + ".s3json?" + EXPORT + refs + "&" + resource + ".project_id__belongs=" + String.join(",", owned)
                + "&start=" + Math.multiplyExact(page, size) + "&limit=" + (size + 1);
            var records = rows(http.delegated(path, credential, null), "project_" + resource, size + 1);
            var values = Json.createArrayBuilder(); var seen = new HashSet<String>();
            for (int i = 0; i < records.size(); i++) {
                var row = records.get(i); String recordId = id(row.getString("@id"));
                if (!seen.add(recordId) || !owned.contains(reference(row, "project_id"))) throw new WebApplicationException(502);
                if (i < size) values.add(tasks ? task(row, organization, disaster) : activity(row, organization, disaster));
            }
            return page(values, page, size, records.size() > size);
        } catch (WebApplicationException e) { throw e; }
          catch (RuntimeException e) { throw new WebApplicationException(502); }
    }
    private List<String> linkedProjects(String eventId, String credential) {
        String body = http.delegated("/event/event/" + id(eventId) + "/project.json?start=0&limit=101", credential, null);
        try (var reader = Json.createReader(new StringReader(body))) {
            var array = reader.readArray();
            if (array.size() > 100) throw new WebApplicationException(503);
            var ids = new LinkedHashSet<String>();
            for (var value : array) if (!ids.add(id(text(value.asJsonObject(), "id")))) throw new WebApplicationException(502);
            return List.copyOf(ids);
        }
    }
    private static JsonObjectBuilder base(JsonObject row, String organization, int disaster) {
        String name = text(row, "name");
        if (name == null || name.isBlank()) throw new WebApplicationException(502);
        return Json.createObjectBuilder().add("id", id(row.getString("@id"))).add("name", name)
            .add("organizationId", organization).add("disasterId", disaster);
    }
    private static JsonObject task(JsonObject row, String organization, int disaster) {
        var value = base(row, organization, disaster);
        nullable(value, "description", text(row, "description"));
        value.add("priorityId", id(text(row, "priority"))).add("statusId", id(text(row, "status")));
        nullable(value, "activityId", reference(row, "activity_id"));
        // pe_id is a super-entity, not a person or HR record ID. Never guess its subtype.
        value.addNull("assignee");
        String due = text(row, "date_due");
        if (due != null) { try { due = OffsetDateTime.parse(due).toString(); } catch (RuntimeException invalid) { due = null; } }
        nullable(value, "dueAt", due);
        number(value, "timeEstimated", text(row, "time_estimated"));
        number(value, "timeActual", text(row, "time_actual"));
        return value.build();
    }
    private static JsonObject activity(JsonObject row, String organization, int disaster) {
        var value = base(row, organization, disaster);
        String status = reference(row, "status_id");
        if (status == null) throw new WebApplicationException(502);
        value.add("statusId", status);
        nullable(value, "locationId", reference(row, "location_id"));
        value.addNull("contactPersonId");
        // Eden Activity stores dates, not instants. Preserve date precision.
        value.addNull("startsAt").addNull("endsAt");
        nullable(value, "startsOn", date(text(row, "date")));
        nullable(value, "endsOn", date(text(row, "end_date")));
        nullable(value, "comments", text(row, "comments"));
        return value.build();
    }
    private static String date(String value) { return value == null ? null : LocalDate.parse(value).toString(); }
    private static void number(JsonObjectBuilder value, String key, String raw) {
        if (raw == null) value.addNull(key);
        else { var amount = new java.math.BigDecimal(raw); if (amount.signum() < 0) throw new WebApplicationException(502); value.add(key, amount); }
    }
    private static JsonObject page(JsonArrayBuilder values, int page, int size, boolean more) {
        return Json.createObjectBuilder().add("items", values).add("page", page).add("pageSize", size).add("hasMore", more).addNull("total").build();
    }
    private static List<JsonObject> rows(String body, String resource, int limit) {
        try (var reader = Json.createReader(new StringReader(body))) {
            var root = reader.readObject(); var array = root.getJsonArray("$_" + resource);
            if (array == null && "0".equals(root.getString("@results", "")) && Set.of("true", "false").contains(root.getString("@success", ""))) return List.of();
            if (array == null || array.size() > limit) throw new WebApplicationException(502);
            return array.stream().map(JsonValue::asJsonObject).toList();
        }
    }
    private static String id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) throw new WebApplicationException(502);
        return value;
    }
    private static String reference(JsonObject row, String key) {
        var field = row.getJsonObject("$k_" + key);
        return field == null || field.isNull("@id") ? null : id(field.getString("@id"));
    }
    private static String text(JsonObject row, String key) {
        var value = row.get(key); if (value instanceof JsonObject object) value = object.get("@value");
        if (value == null || value == JsonValue.NULL) return null;
        if (value instanceof JsonString string) return string.getString();
        if (value instanceof JsonNumber number) return number.toString();
        throw new WebApplicationException(502);
    }
    private static void nullable(JsonObjectBuilder builder, String key, String value) {
        if (value == null) builder.addNull(key); else builder.add(key, value);
    }
}
