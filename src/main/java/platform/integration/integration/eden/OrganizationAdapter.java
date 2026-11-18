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
@ApplicationScoped
public class OrganizationAdapter implements OrganizationPort {
    @Inject EdenHttpClient http;
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
                if(i<size)items.add(new OrganizationDto(id,name,types.size()==1?types.getFirst():null,types,text(row,"acronym"),text(row,"country"),locations.size()==1?locations.getFirst():null,text(row,"phone"),text(row,"email"),text(row,"comments")));
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
