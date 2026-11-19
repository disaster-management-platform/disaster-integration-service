package platform.integration.domain;

import jakarta.json.*;
import jakarta.ws.rs.BadRequestException;
import java.util.Set;

public record OrganizationInput(String name,String acronym,String country,String typeId,String locationId,
                                String phone,String email,String description) {
    public static OrganizationInput parse(JsonObject input) {
        if(input==null||!Set.of("name","acronym","country","typeId","locationId","phone","email","description").containsAll(input.keySet()))throw new BadRequestException();
        String name=field(input,"name",128), country=field(input,"country",2);
        if(name==null||name.isBlank()||country!=null&&!country.matches("[A-Z]{2}"))throw new BadRequestException();
        return new OrganizationInput(name,field(input,"acronym",16),country,id(input,"typeId"),id(input,"locationId"),field(input,"phone",200),field(input,"email",254),field(input,"description",4000));
    }
    private static String id(JsonObject input,String field){String value=field(input,field,18);if(value!=null&&!value.matches("[1-9][0-9]{0,17}"))throw new BadRequestException();return value;}
    private static String field(JsonObject input,String field,int limit){
        if(!input.containsKey(field)||input.isNull(field))return null;
        if(!(input.get(field) instanceof JsonString s)||s.getString().length()>limit||s.getString().indexOf(0)>=0)throw new BadRequestException();
        return s.getString();
    }
}
