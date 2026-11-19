package platform.integration.integration.eden;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.*;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
@ApplicationScoped
public class OrganizationAdapter implements OrganizationPort {
    @Inject EdenHttpClient http;
    @Inject EdenDelegations delegations;
    private static final String DETAILS="show_ids=true&maxdepth=0&references=location_id,organisation_type_id&mcomponents=organisation_location,organisation_organisation_type,tag";
    public OrganizationPage findOrganizations(String query,int page,int size){
        String path="/org/organisation.s3json?show_ids=true&maxdepth=0&references=location_id,organisation_type_id&mcomponents=organisation_location,organisation_organisation_type&start="+Math.multiplyExact(page,size)+"&limit="+(size+1);
        if(query!=null)path+="&organisation.name__contains="+URLEncoder.encode(query,StandardCharsets.UTF_8);
        return translate(page, size, http.get(path));
    }
    private OrganizationPage translate(int page, int size, String body) {
        try(var reader=Json.createReader(new StringReader(body))){
            var envelope=reader.readObject();var rows=envelope.getJsonArray("$_org_organisation");
            if(rows==null&&"0".equals(envelope.getString("@results",""))&&Set.of("true","false").contains(envelope.getString("@success","")))return new OrganizationPage(List.of(),page,size,null,false);
            if(rows==null||rows.size()>size+1)throw new WebApplicationException(502);
            var items=new ArrayList<OrganizationDto>();
            for(int i=0;i<rows.size();i++){
                var row=rows.getJsonObject(i);String id=id(row.getString("@id"));String name=text(row,"name");
                if(name==null)throw new WebApplicationException(502);
                var types=references(row,"$_org_organisation_organisation_type","organisation_type_id");
                var locations=references(row,"$_org_organisation_location","location_id");
                if(i<size)items.add(new OrganizationDto(id,name,types.size()==1?types.getFirst():null,types,text(row,"acronym"),text(row,"country"),locations.size()==1?locations.getFirst():null,text(row,"phone"),text(row,"email"),text(row,"comments"),null,null,null,null));
            }
            return new OrganizationPage(items,page,size,null,rows.size()>size);
        }catch(WebApplicationException failure){throw failure;}catch(RuntimeException failure){throw new WebApplicationException(502);}
    }
    public OrganizationDto findOrganization(String id) {
        String path = "/org/organisation/" + id + ".s3json?show_ids=true&maxdepth=0&references=location_id,organisation_type_id&mcomponents=organisation_location,organisation_organisation_type";
        var page = translate(0, 1, http.get(path));
        if (page.items().isEmpty()) throw new WebApplicationException(404);
        if (page.hasMore() || !page.items().getFirst().id().equals(id)) throw new WebApplicationException(502);
        return page.items().getFirst();
    }

    public OrganizationDto detail(String organization,String id){
        var record=findOrganization(id);
        if(!delegations.represents(organization,id))return record;
        String credential=delegations.coordinationAuthorization(organization);
        var persons=new LinkedHashSet<String>();var volunteers=new LinkedHashSet<String>();var facilities=new LinkedHashSet<String>();
        for(var row:related("/hrm/human_resource.s3json?show_ids=true&maxdepth=0&mcomponents=none&references=organisation_id&human_resource.organisation_id="+id,credential,"@id")){
            if(!id.equals(number(row,"organisation_id")))throw new WebApplicationException(502);
            String personnel=number(row,"@id");
            if("2".equals(number(row,"type")))volunteers.add(personnel);else persons.add(personnel);
        }
        for(var row:related("/org/site.json?site.organisation_id="+id,credential,"site_id")){
            if(!id.equals(number(row,"organisation_id")))throw new WebApplicationException(502);facilities.add(number(row,"site_id"));
        }
        return new OrganizationDto(record.id(),record.name(),record.typeId(),record.typeIds(),record.acronym(),record.country(),record.locationId(),record.phone(),record.email(),record.description(),List.copyOf(persons),List.copyOf(volunteers),List.copyOf(facilities),null);
    }
    private static String number(JsonObject row,String field){var value=row.get(field);if(value==null&&row.getJsonObject("$k_"+field)!=null)value=row.getJsonObject("$k_"+field).get("@id");if(value instanceof JsonObject o)value=o.get("@value");if(value instanceof JsonNumber n)return id(n.toString());if(value instanceof JsonString s)return id(s.getString());throw new WebApplicationException(502);}
    private List<JsonObject> related(String path,String credential,String key){
        var result=new ArrayList<JsonObject>();var seen=new HashSet<String>();
        for(int start=0;start<10000;start+=100){
            try(var reader=Json.createReader(new StringReader(http.delegated(path+"&orderby="+(key.equals("@id")?"id":key)+"&start="+start+"&limit=100",credential,null)))){
                var body=reader.readValue();
                JsonArray rows;
                if(body instanceof JsonArray array)rows=array;
                else if(body instanceof JsonObject object){rows=object.getJsonArray("$_hrm_human_resource");if(rows==null&&"0".equals(object.getString("@results","")))rows=Json.createArrayBuilder().build();}
                else throw new WebApplicationException(502);
                if(rows==null||rows.size()>100)throw new WebApplicationException(502);
                for(var value:rows){var row=value.asJsonObject();if(!seen.add(number(row,key)))throw new WebApplicationException(502);result.add(row);}
                if(rows.size()<100)return result;
            }catch(WebApplicationException e){throw e;}catch(RuntimeException e){throw new WebApplicationException(502);}
        }
        throw new WebApplicationException(503);
    }
    private JsonObject nativeRecord(String query,String credential){
        try(var reader=Json.createReader(new StringReader(http.delegated(query,credential,null)))){
            var root=reader.readObject();var rows=root.getJsonArray("$_org_organisation");
            if(rows==null&&"0".equals(root.getString("@results","")))return null;
            if(rows==null||rows.size()>1)throw new WebApplicationException(502);return rows.isEmpty()?null:rows.getJsonObject(0);
        }catch(WebApplicationException e){throw e;}catch(RuntimeException e){throw new WebApplicationException(502);}
    }
    private JsonObject byUuid(UUID operation,String credential){
        var row=nativeRecord("/org/organisation.s3json?"+DETAILS+"&organisation.uuid="+URLEncoder.encode("urn:uuid:"+operation,StandardCharsets.UTF_8)+"&limit=2",credential);
        if(row!=null&&!("urn:uuid:"+operation).equals(row.getString("@uuid","")))throw new WebApplicationException(502);return row;
    }
    private boolean marked(JsonObject row,String marker){var tags=row.getJsonArray("$_org_organisation_tag");return tags!=null&&tags.stream().map(JsonValue::asJsonObject).anyMatch(t->marker.equals(text(t,"tag")));}
    private void link(JsonObjectBuilder value,JsonObject current,String component,String field,String related,UUID operation){
        if(related==null)return;
        var rows=current==null?null:current.getJsonArray("$_"+component);
        var entry=Json.createObjectBuilder();
        if(rows!=null&&!rows.isEmpty()){
            if(rows.stream().map(JsonValue::asJsonObject).anyMatch(row->related.equals(row.getJsonObject("$k_"+field).getString("@id"))))return;
            // Do not silently remove multiple native relationships via an ambiguous scalar input.
            if(rows.size()!=1)throw new WebApplicationException(409);
            entry.add("@uuid",rows.getJsonObject(0).getString("@uuid"));
        }else entry.add("@uuid","urn:uuid:"+OperationIdentity.key(operation.toString(),component));
        entry.add(field,Json.createObjectBuilder().add("@value",related));
        value.add("$_"+component,Json.createArrayBuilder().add(entry));
    }
    private static void put(JsonObjectBuilder value,String field,String text){if(text!=null)value.add(field,text);}
    public OrganizationDto write(String organization,String target,OrganizationInput input,UUID operation,BooleanSupplier beginWrite){
        if(target!=null&&!delegations.represents(organization,target))throw new WebApplicationException(403);
        String credential=target==null?delegations.organizationCreator():delegations.coordinationAuthorization(organization);
        String path="/org/organisation"+(target==null?"":"/"+target)+".s3json";
        JsonObject current=target==null?byUuid(operation,credential):nativeRecord(path+"?"+DETAILS,credential);
        if(target!=null&&(current==null||!target.equals(current.getString("@id"))))throw new WebApplicationException(404);
        String marker="platform.operation."+operation;
        if(current!=null&&(target==null||marked(current,marker)))return findOrganization(id(current.getString("@id")));
        var value=Json.createObjectBuilder().add("name",input.name()).add("@uuid",current==null?"urn:uuid:"+operation:current.getString("@uuid"));
        put(value,"acronym",input.acronym());put(value,"country",input.country());put(value,"phone",input.phone());put(value,"email",input.email());put(value,"comments",input.description());
        link(value,current,"org_organisation_organisation_type","organisation_type_id",input.typeId(),operation);
        link(value,current,"org_organisation_location","location_id",input.locationId(),operation);
        if(target!=null)value.add("$_org_organisation_tag",Json.createArrayBuilder().add(Json.createObjectBuilder().add("@uuid","urn:uuid:"+operation).add("tag",marker).add("value","completed")));
        if(!beginWrite.getAsBoolean())throw new WebApplicationException(503);
        try(var reader=Json.createReader(new StringReader(http.delegated(path,credential,Json.createObjectBuilder().add("$_org_organisation",Json.createArrayBuilder().add(value)).build().toString())))){
            if(!"success".equals(reader.readObject().getString("status","")))throw new WebApplicationException(502);
        }
        var result=target==null?byUuid(operation,credential):nativeRecord(path+"?"+DETAILS,credential);
        if(result==null||target!=null&&(!target.equals(result.getString("@id"))||!marked(result,marker)))throw new WebApplicationException(503);
        return findOrganization(id(result.getString("@id")));
    }
    public OrganizationTypePage findOrganizationTypes(int page, int size) {
        String body = http.get("/org/organisation_type.json?start=" + Math.multiplyExact(page, size) + "&limit=" + (size + 1));
        try (var reader = Json.createReader(new StringReader(body))) {
            var array = reader.readArray();
            if (array.size() > size + 1) throw new WebApplicationException(502);
            var types = new ArrayList<OrganizationTypeDto>();
            for (int i = 0; i < Math.min(size, array.size()); i++) {
                var row = array.getJsonObject(i);
                String typeId = row.get("id") instanceof JsonNumber number ? number.toString() : row.getString("id", "");
                String name = row.getString("name", "");
                if (!typeId.matches("[1-9][0-9]{0,17}") || name.isBlank()) throw new WebApplicationException(502);
                types.add(new OrganizationTypeDto(typeId, name));
            }
            return new OrganizationTypePage(types, page, size, null, array.size() > size);
        } catch (WebApplicationException failure) { throw failure; }
          catch (RuntimeException invalid) { throw new WebApplicationException(502); }
    }

    static boolean validIdentity(JsonObject object){try{id(object.get("id").toString().replace("\"",""));return !object.getString("name").isBlank();}catch(RuntimeException failure){return false;}}
    private static String id(String value){if(!value.matches("[1-9][0-9]{0,17}"))throw new WebApplicationException(502);return value;}
    private List<String> references(JsonObject row,String component,String field){
        if(!row.containsKey(component))return List.of();
        var ids=new LinkedHashSet<String>();
        for(var value:row.getJsonArray(component)){var ref=value.asJsonObject().getJsonObject("$k_"+field);if(ref==null)throw new WebApplicationException(502);ids.add(id(ref.getString("@id")));}
        return List.copyOf(ids);
    }
    private String text(JsonObject row,String field){
        JsonValue value=row.get(field);if(value==null||value==JsonValue.NULL)return null;
        if(value instanceof JsonObject object)value=object.get("@value");
        if(value==null||value==JsonValue.NULL)return null;
        if(!(value instanceof JsonString text))throw new WebApplicationException(502);
        return text.getString().isBlank()?null:text.getString();
    }
}
