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
        try {
        String path="/org/organisation.json?start="+Math.multiplyExact(page,size)+"&limit="+(size+1);
        if(query!=null)path+="&organisation.name__contains="+URLEncoder.encode(query,StandardCharsets.UTF_8);
        var array=array(http.get(path));
        if(array.size()>size+1)throw new WebApplicationException(502);
        var items=new ArrayList<OrganizationDto>();
        for(int i=0;i<array.size();i++){
            var obj=array.getJsonObject(i);
            if(!validIdentity(obj))throw new WebApplicationException(502);
            var types=ids(obj.get("org_organisation_organisation_type.organisation_type_id"));
            if(i>=size)continue;
            var locations=array(http.get("/org/organisation/"+id(obj.get("id"))+"/organisation_location.json?start=0&limit=2"));
            var locationIds=new ArrayList<String>();
            for(var value:locations){var location=value.asJsonObject();locationIds.add(id(location.get("location_id")));}
            items.add(new OrganizationDto(id(obj.get("id")),obj.getString("name"),types.size()==1?types.getFirst():null,List.copyOf(types),text(obj,"acronym"),text(obj,"country"),locationIds.size()==1?locationIds.getFirst():null,text(obj,"phone"),text(obj,"email"),text(obj,"comments")));
        }
        return new OrganizationPage(items,page,size,null,array.size()>size);
        }catch(WebApplicationException failure){throw failure;}catch(RuntimeException failure){throw new WebApplicationException(502);}
    }
    private static JsonArray array(String body){
        try(var reader=Json.createReader(new StringReader(body))){return reader.readArray();}
        catch(RuntimeException failure){throw new WebApplicationException(502);}
    }
    static boolean validIdentity(JsonObject obj){try{id(obj.get("id"));return !obj.getString("name").isBlank();}catch(RuntimeException failure){return false;}}
    private static String id(JsonValue value){
        String id=value instanceof JsonNumber number?number.toString():value instanceof JsonString text?text.getString():"";
        if(!id.matches("[1-9][0-9]*"))throw new WebApplicationException(502);
        return id;
    }
    private static List<String> ids(JsonValue value){
        if(value==null||value==JsonValue.NULL)return List.of();
        if(value instanceof JsonArray array)return array.stream().map(OrganizationAdapter::id).distinct().toList();
        return List.of(id(value));
    }
    private static String text(JsonObject obj,String key){
        if(!obj.containsKey(key)||obj.isNull(key))return null;
        if(!(obj.get(key) instanceof JsonString value))throw new WebApplicationException(502);
        return value.getString().isBlank()?null:value.getString();
    }
}
