package platform.integration.integration.eden;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.CoordinationPort;
import platform.integration.domain.CoordinationInput;
import java.util.function.BooleanSupplier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            Set<String> owned = ownedProjects(eventId, credential, nativeOrg);
            if (owned.isEmpty()) return page(Json.createArrayBuilder(), page, size, false);
            String resource = tasks ? "task" : "activity";
            String refs = tasks ? "project_id,activity_id,pe_id" : "project_id,status_id,location_id,person_id";
            String path = "/project/" + resource + ".s3json?" + EXPORT + refs + "&" + resource + ".project_id__belongs=" + String.join(",", owned)
                + "&start=" + Math.multiplyExact(page, size) + "&limit=" + (size + 1);
            var records = rows(http.delegated(path, credential, null), "project_" + resource, size + 1);
            var values = Json.createArrayBuilder(); var seen = new HashSet<String>();
            var visible = records.subList(0, Math.min(size, records.size()));
            var relations = relations(visible,tasks,credential,nativeOrg);
            for (int i = 0; i < records.size(); i++) {
                var row = records.get(i); String recordId = id(row.getString("@id"));
                if (!seen.add(recordId) || !owned.contains(reference(row, "project_id"))) throw new WebApplicationException(502);
                if (i < size) values.add(view(row, organization, disaster, tasks, relations));
            }
            return page(values, page, size, records.size() > size);
        } catch (WebApplicationException e) { throw e; }
          catch (RuntimeException e) { throw new WebApplicationException(502); }
    }
    private Set<String> ownedProjects(String eventId,String credential,String nativeOrg) {
            List<String> linked = linkedProjects(eventId, credential);
            if (linked.isEmpty()) return Set.of();
            var projects = rows(http.delegated("/project/project.s3json?" + EXPORT + "organisation_id&project.id__belongs=" + String.join(",", linked) + "&start=0&limit=101", credential, null), "project_project", 101);
            if (projects.size() > 100) throw new WebApplicationException(503);
            var owned = new LinkedHashSet<String>();
            for (var project : projects) {
                if (!linked.contains(id(project.getString("@id")))) throw new WebApplicationException(502);
                String org = reference(project, "organisation_id");
                if (org == null) throw new WebApplicationException(502);
                if (nativeOrg.equals(org)) owned.add(id(project.getString("@id")));
            }
        return owned;
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
    private static String resource(boolean task) { return task ? "task" : "activity"; }
    private static String recordTable(boolean task) { return "project_" + resource(task); }
    private String detailQuery(boolean task) {
        return "?show_ids=true&maxdepth=0&mcomponents=tag&references=" + (task ? "project_id,activity_id,pe_id" : "project_id,status_id,location_id,person_id");
    }
    private JsonObject nativeRecord(boolean task,String target,String credential,Set<String> owned) {
        var result = rows(http.delegated("/project/"+resource(task)+"/"+id(target)+".s3json"+detailQuery(task),credential,null),recordTable(task),2);
        if (result.isEmpty()) throw new WebApplicationException(404);
        if (result.size()!=1 || !target.equals(id(result.getFirst().getString("@id")))) throw new WebApplicationException(502);
        var row=result.getFirst();
        if (!owned.contains(reference(row,"project_id"))) throw new WebApplicationException(404);
        return row;
    }
    private JsonObject byUuid(boolean task,UUID operation,String credential,Set<String> owned) {
        var result=rows(http.delegated("/project/"+resource(task)+".s3json"+detailQuery(task)+"&"+resource(task)+".uuid="+URLEncoder.encode("urn:uuid:"+operation,StandardCharsets.UTF_8)+"&limit=2",credential,null),recordTable(task),2);
        if(result.size()>1)throw new WebApplicationException(502);
        if(result.isEmpty())return null;
        var row=result.getFirst();if(!owned.contains(reference(row,"project_id"))||!("urn:uuid:"+operation).equals(row.getString("@uuid","")))throw new WebApplicationException(502);
        return row;
    }
    public JsonObject get(String organization,int disaster,String eventId,boolean task,String id) {
        String credential=delegations.coordinationAuthorization(organization), nativeOrg=delegations.nativeOrganization(organization);
        return view(nativeRecord(task,id,credential,ownedProjects(eventId,credential,nativeOrg)),organization,disaster,task,credential,nativeOrg);
    }
    public JsonObject write(String organization,String eventId,boolean task,String target,CoordinationInput input,UUID operation,BooleanSupplier beginWrite) {
        String credential=delegations.coordinationAuthorization(organization), nativeOrg=delegations.nativeOrganization(organization);
        Set<String> owned=ownedProjects(eventId,credential,nativeOrg);
        JsonObject current=target==null?byUuid(task,operation,credential,owned):nativeRecord(task,target,credential,owned);
        String marker="platform.operation."+operation;
        String project=current!=null?reference(current,"project_id"):input.projectId();
        if(project==null){if(owned.size()!=1)throw new WebApplicationException(409);project=owned.iterator().next();}
        if(!owned.contains(project)||input.projectId()!=null&&!project.equals(input.projectId()))throw new WebApplicationException(403);
        if(current!=null && (target==null || marked(current,task,marker)))return view(current,organization,input.disasterId(),task,credential,nativeOrg);
        var value=Json.createObjectBuilder().add("name",input.name()).add("@uuid",current==null?"urn:uuid:"+operation:current.getString("@uuid"));
        ref(value,"project_id",project);
        if(task){
            data(value,"description",input.description());data(value,"priority",input.priorityId());data(value,"status",input.statusId());data(value,"date_due",input.dueAt());
            if(input.timeEstimated()!=null)value.add("time_estimated",input.timeEstimated());
            if(input.activityId()!=null){var activity=nativeRecord(false,input.activityId(),credential,owned);if(!project.equals(reference(activity,"project_id")))throw new WebApplicationException(403);ref(value,"activity_id",input.activityId());}
            if(input.assignee()!=null)ref(value,"pe_id",assigneePe(input.assignee(),credential,nativeOrg));
        }else{
            ref(value,"status_id",input.statusId());
            if(input.locationId()!=null){plainRecord("/gis/location",input.locationId(),credential);ref(value,"location_id",input.locationId());}
            if(input.contactPersonId()!=null){requirePersonnel(input.contactPersonId(),credential,nativeOrg);ref(value,"person_id",input.contactPersonId());}
            data(value,"date",input.startsOn());data(value,"end_date",input.endsOn());data(value,"comments",input.comments());
        }
        if(target!=null)value.add("$_"+recordTable(task)+"_tag",Json.createArrayBuilder().add(Json.createObjectBuilder().add("@uuid","urn:uuid:"+operation).add("tag",marker).add("value","completed")));
        if(!beginWrite.getAsBoolean())throw new WebApplicationException(503);
        String payload=Json.createObjectBuilder().add("$_"+recordTable(task),Json.createArrayBuilder().add(value)).build().toString();
        String path="/project/"+resource(task)+(target==null?"":"/"+target)+".s3json";
        try(var reader=Json.createReader(new StringReader(http.delegated(path,credential,payload)))){
            if(!"success".equals(reader.readObject().getString("status","")))throw new WebApplicationException(502);
        }
        JsonObject result=target==null?byUuid(task,operation,credential,owned):nativeRecord(task,target,credential,owned);
        if(result==null||!project.equals(reference(result,"project_id"))||target!=null&&!marked(result,task,marker))throw new WebApplicationException(503);
        return view(result,organization,input.disasterId(),task,credential,nativeOrg);
    }
    private static boolean marked(JsonObject row,boolean task,String marker){
        var tags=row.getJsonArray("$_project_"+(task?"task":"activity")+"_tag");
        return tags!=null&&tags.stream().anyMatch(tag->marker.equals(text(tag.asJsonObject(),"tag")));
    }
    private static void ref(JsonObjectBuilder value,String field,String id){value.add(field,Json.createObjectBuilder().add("@value",id));}
    private static void data(JsonObjectBuilder value,String field,String text){if(text!=null)value.add(field,text);}
    private JsonArray plain(String path,String credential){
        try(var reader=Json.createReader(new StringReader(http.delegated(path,credential,null)))){return reader.readArray();}
        catch(WebApplicationException e){throw e;}catch(RuntimeException e){throw new WebApplicationException(502);}
    }
    private JsonObject plainRecord(String path,String id,String credential){
        var records=plain(path+"/"+id(id)+".json?limit=2",credential);
        if(records.isEmpty())throw new WebApplicationException(404);
        if(records.size()!=1||!id.equals(text(records.getJsonObject(0),"id")))throw new WebApplicationException(502);
        return records.getJsonObject(0);
    }
    private void requirePersonnel(String person,String credential,String org){
        if(!personnel(List.of(id(person)),credential,org).contains(person))throw new WebApplicationException(403);
    }
    private String assigneePe(JsonObject assignee,String credential,String org){
        String id=assignee.getString("id"),kind=assignee.getString("kind");
        JsonObject resolved;
        switch(kind){
            case "person" -> {requirePersonnel(id,credential,org);resolved=plainRecord("/pr/person",id,credential);}
            case "organization" -> {if(!id.equals(org))throw new WebApplicationException(403);resolved=plainRecord("/org/organisation",id,credential);}
            case "team" -> {
                resolved=plainRecord("/pr/group",id,credential);
                if(!"3".equals(text(resolved,"group_type")))throw new WebApplicationException(403);
            }
            default -> throw new WebApplicationException(400);
        }
        return id(text(resolved,"pe_id"));
    }
    private record Relations(Map<String,JsonObject> assignees, Set<String> people) {}
    private Set<String> personnel(Collection<String> ids,String credential,String org){
        if(ids.isEmpty())return Set.of();
        var result=new HashSet<String>();
        for(int offset=0;offset<10000;offset+=100){
            var rows=plain("/hrm/human_resource.json?human_resource.person_id__belongs="+String.join(",",ids)+"&human_resource.organisation_id="+id(org)+"&start="+offset+"&limit=100",credential);
            if(rows.size()>100)throw new WebApplicationException(502);
            for(var item:rows){var row=item.asJsonObject();String person=text(row,"person_id");if(!org.equals(text(row,"organisation_id"))||!ids.contains(person))throw new WebApplicationException(502);result.add(person);}
            if(rows.size()<100)return result;
        }
        throw new WebApplicationException(503);
    }
    private static String assigneeReference(JsonObject row){
        var reference=row.getJsonObject("$k_pe_id");
        if(reference==null){if(text(row,"pe_id")!=null)throw new WebApplicationException(502);return null;}
        String resource=reference.getString("@resource","");
        if(!Set.of("pr_person","pr_group","org_organisation").contains(resource))throw new WebApplicationException(502);
        return resource+"/"+id(reference.getString("@id"));
    }
    private Relations relations(List<JsonObject> rows,boolean task,String credential,String org){
        var ids=new LinkedHashSet<String>();
        for(var row:rows){String value=task?assigneeReference(row):reference(row,"person_id");if(value!=null)ids.add(value);}
        if(ids.isEmpty())return new Relations(Map.of(),Set.of());
        if(!task)return new Relations(Map.of(),personnel(ids,credential,org));
        var persons=new LinkedHashSet<String>();var teams=new LinkedHashSet<String>();
        var result=new HashMap<String,JsonObject>();
        for(String key:ids){
            var reference=key.split("/",2);String kind=reference[0],identity=reference[1];
            if(kind.equals("pr_person"))persons.add(identity);
            else if(kind.equals("pr_group"))teams.add(identity);
            else if(identity.equals(org))result.put(key,Json.createObjectBuilder().add("kind","organization").add("id",identity).build());
            else throw new WebApplicationException(502);
        }
        Set<String> allowed=personnel(persons,credential,org);
        for(String person:allowed)result.put("pr_person/"+person,Json.createObjectBuilder().add("kind","person").add("id",person).build());
        if(!teams.isEmpty()){
            var groups=plain("/pr/group.json?group.id__belongs="+String.join(",",teams)+"&limit=101",credential);
            if(groups.size()>100)throw new WebApplicationException(503);
            for(var item:groups){var row=item.asJsonObject();String team=id(text(row,"id"));
                if(!teams.contains(team)||!"3".equals(text(row,"group_type")))throw new WebApplicationException(502);
                result.put("pr_group/"+team,Json.createObjectBuilder().add("kind","team").add("id",team).build());}
        }
        if(result.size()!=ids.size())throw new WebApplicationException(502);
        return new Relations(result,allowed);
    }
    private JsonObject view(JsonObject row,String organization,int disaster,boolean task,String credential,String nativeOrg){return view(row,organization,disaster,task,relations(List.of(row),task,credential,nativeOrg));}
    private JsonObject view(JsonObject row,String organization,int disaster,boolean task,Relations relations){
        var value=Json.createObjectBuilder(task?task(row,organization,disaster):activity(row,organization,disaster));
        value.add("projectId",reference(row,"project_id"));
        if(task){String pe=assigneeReference(row);if(pe!=null&&relations.assignees().containsKey(pe))value.add("assignee",relations.assignees().get(pe));}
        else {String person=reference(row,"person_id");if(person!=null&&relations.people().contains(person))value.add("contactPersonId",person);}
        return value.build();
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
