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
public class PeopleAdapter implements PeoplePort {
    private static final String EXPORT="show_ids=true&maxdepth=0&references=location_id&mcomponents=contact,person_tag";
    @Inject EdenHttpClient http;
    @Inject EdenDelegations delegations;
    private JsonObject object(String body){try(var reader=Json.createReader(new StringReader(body))){return reader.readObject();}catch(RuntimeException failure){throw new WebApplicationException(502);}}
    private String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}
    private List<JsonObject> exported(String organization,String path,String key){
        JsonObject result=object(http.delegated(path,delegations.authorization(organization),null));
        var values=result.getJsonArray(key);if(values==null){if("0".equals(result.getString("@results",""))&&Set.of("true","false").contains(result.getString("@success","")))return List.of();throw new WebApplicationException(502);}
        var rows=new ArrayList<JsonObject>();for(var value:values){var row=value.asJsonObject();id(row.getString("@id"));rows.add(row);}return rows;
    }
    public List<PeopleRecord> list(String organization,String search,int offset,int limit,List<String> ids){
        String path="/pr/person.s3json?show_ids=true&maxdepth=0&references=location_id&mcomponents=none&orderby=id&start="+offset+"&limit="+limit;
        if(ids.isEmpty())return List.of();ids.forEach(this::id);path+="&person.id__belongs="+String.join(",",ids);
        if(search!=null)path+="&person.first_name__like="+encode("%"+search+"%");
        var rows=exported(organization,path,"$_pr_person");if(rows.size()>limit)throw new WebApplicationException(502);return rows.stream().map(row->record(organization,row)).toList();
    }
    private List<JsonObject> related(String organization,String path,String selector,List<String> ids,String table,String refs,String filter){
        var result=new ArrayList<JsonObject>();
        for(int start=0;;start+=100){
            var page=exported(organization,path+".s3json?show_ids=true&maxdepth=0&mcomponents=none&references="+refs+"&"+selector+"__belongs="+String.join(",",ids)+"&orderby=id&start="+start+"&limit=100"+(filter==null?"":"&"+filter),"$_"+table);
            if(page.size()>100)throw new WebApplicationException(502);
            result.addAll(page);if(page.size()<100)return result;
        }
    }
    public JsonObject relationships(String organization,List<String> ids){
        if(ids.isEmpty())return Json.createObjectBuilder().build();ids.forEach(this::id);
        var memberships=new HashMap<String,List<String>>();
        for(String person:ids)memberships.put(person,new ArrayList<>());
        var familyMemberships=related(organization,"/dvr/group_membership","group_membership.person_id",ids,
            "pr_group_membership","person_id,group_id","group_membership.group_id%24group_type=7");
        for(var membership:familyMemberships){
            String person=reference(membership,"person_id"),group=reference(membership,"group_id");
            if(!memberships.containsKey(person)||group==null)throw new WebApplicationException(502);
            memberships.get(person).add(group);
        }
        var cases=related(organization,"/dvr/case","case.person_id",ids,"dvr_case","person_id,status_id",null);
        var shelters=related(organization,"/cr/shelter_registration","shelter_registration.person_id",ids,"cr_shelter_registration","person_id,shelter_id",null);
        var activities=related(organization,"/dvr/case_activity","case_activity.person_id",ids,"dvr_case_activity","person_id,need_id,status_id",null);
        var result=Json.createObjectBuilder();
        for(String person:ids){
            var familyIds=memberships.get(person).stream().distinct().sorted().toList();
            var caseIds=Json.createArrayBuilder();var statuses=new HashSet<String>();int open=0;
            for(var row:cases){String owner=reference(row,"person_id");if(!ids.contains(owner))throw new WebApplicationException(502);if(!person.equals(owner))continue;
                caseIds.add(row.getString("@id"));String status=reference(row,"status_id");if(status==null)throw new WebApplicationException(502);statuses.add(status);
                if(!"true".equalsIgnoreCase(raw(row,"archived"))&&raw(row,"closed_on")==null)open++;
            }
            var shelterIds=new TreeSet<String>();
            for(var row:shelters){String owner=reference(row,"person_id");if(!ids.contains(owner))throw new WebApplicationException(502);if(person.equals(owner)&&"2".equals(raw(row,"registration_status"))){String shelter=reference(row,"shelter_id");if(shelter==null)throw new WebApplicationException(502);shelterIds.add(shelter);}}
            var needs=Json.createArrayBuilder();
            for(var row:activities){String owner=reference(row,"person_id");if(!ids.contains(owner))throw new WebApplicationException(502);if(!person.equals(owner))continue;
                var activity=Json.createObjectBuilder().add("id",row.getString("@id"));nullable(activity,"needId",reference(row,"need_id"));nullable(activity,"subject",raw(row,"subject"));nullable(activity,"needDetails",raw(row,"need_details"));nullable(activity,"careDetails",raw(row,"activity_details"));nullable(activity,"statusId",reference(row,"status_id"));needs.add(activity);
            }
            var relation=Json.createObjectBuilder().add("familyIds",Json.createArrayBuilder(familyIds)).add("caseIds",caseIds).add("shelterIds",Json.createArrayBuilder(shelterIds)).add("openCases",open).add("needs",needs);
            nullable(relation,"familyId",familyIds.size()==1?familyIds.getFirst():null);nullable(relation,"caseStatusId",statuses.size()==1?statuses.iterator().next():null);nullable(relation,"shelterId",shelterIds.size()==1?shelterIds.first():null);
            result.add(person,relation);
        }
        return result.build();
    }
    public PeopleRecord get(String organization,String person){return record(organization,nativeGet(organization,person));}
    private JsonObject nativeGet(String organization,String person){
        var rows=exported(organization,"/pr/person/"+id(person)+".s3json?"+EXPORT,"$_pr_person");
        if(rows.isEmpty())throw new WebApplicationException(404);if(rows.size()!=1)throw new WebApplicationException(502);return rows.getFirst();
    }
    private JsonObject byUuid(String organization,UUID uuid,String type){
        var rows=exported(organization,"/"+(type.equals("pr_person")?"pr/person":"event/event")+".s3json?"+(type.equals("pr_person")?EXPORT:"show_ids=true&mcomponents=none&references=none")+"&"+(type.equals("pr_person")?"person":"event")+".uuid="+encode("urn:uuid:"+uuid)+"&limit=2","$_"+type);
        if(rows.size()>1)throw new WebApplicationException(502);return rows.isEmpty()?null:rows.getFirst();
    }
    private void persist(String organization,String path,String type,JsonObject record){
        JsonObject result=object(http.delegated(path,delegations.authorization(organization),Json.createObjectBuilder().add("$_"+type,Json.createArrayBuilder().add(record)).build().toString()));
        if(!"success".equals(result.getString("status",""))){
            String code=result.getString("statuscode","");
            if("failed".equals(result.getString("status",""))&&Set.of("400","409","422").contains(code))throw new WebApplicationException("409".equals(code)?409:422);
            throw new WebApplicationException(502);
        }
    }
    public PeopleRecord create(String organization,UUID operation,PeopleInput input){
        var existing=byUuid(organization,operation,"pr_person");if(existing!=null)return record(organization,existing);
        var value=command(input).add("@uuid","urn:uuid:"+operation);contacts(value,null,input,operation);
        persist(organization,"/pr/person.s3json","pr_person",value.build());
        var result=byUuid(organization,operation,"pr_person");if(result==null)throw new WebApplicationException(503);return record(organization,result);
    }
    public PeopleRecord update(String organization,String person,PeopleInput input,UUID operation,UUID expectedIdentity,java.util.function.BooleanSupplier beginWrite){
        JsonObject current=nativeGet(organization,person);
        if(!expectedIdentity.equals(record(organization,current).identity()))throw new WebApplicationException(404);
        String marker="platform.operation."+operation;
        for(var tag:array(current,"$_pr_person_tag"))if(marker.equals(raw(tag.asJsonObject(),"tag")))return record(organization,current);
        var value=command(input).add("@uuid",current.getString("@uuid"));contacts(value,current,input,operation);
        value.add("$_pr_person_tag",Json.createArrayBuilder().add(Json.createObjectBuilder().add("@uuid","urn:uuid:"+operation).add("tag",marker).add("value","completed")));
        if(!beginWrite.getAsBoolean())throw new WebApplicationException(503);
        persist(organization,"/pr/person/"+id(person)+".s3json","pr_person",value.build());return get(organization,person);
    }
    private JsonObjectBuilder command(PeopleInput input){
        var value=Json.createObjectBuilder();
        put(value,"first_name",input.firstName());put(value,"last_name",input.lastName());put(value,"pe_label",input.identificationRef());put(value,"date_of_birth",input.birthDate());
        if(input.locationId()!=null)value.add("location_id",Json.createObjectBuilder().add("@value",id(input.locationId())));
        return value;
    }
    private void contacts(JsonObjectBuilder record,JsonObject current,PeopleInput input,UUID operation){
        var values=Json.createArrayBuilder();
        for(String method:List.of("SMS","EMAIL")){
            String content=method.equals("SMS")?input.phone():input.email();if(content==null)continue;
            String uuid="urn:uuid:"+UUID.nameUUIDFromBytes((operation+method).getBytes(StandardCharsets.UTF_8));
            if(current!=null)for(var value:array(current,"$_pr_contact")){var contact=value.asJsonObject();if(method.equals(raw(contact,"contact_method"))){uuid=contact.getString("@uuid");break;}}
            values.add(Json.createObjectBuilder().add("@uuid",uuid).add("contact_method",Json.createObjectBuilder().add("@value",method)).add("value",content));
        }
        record.add("$_pr_contact",values);
    }
    public String event(String organization,UUID operation,int disaster,String name,String source){
        var existing=byUuid(organization,operation,"event_event");if(existing!=null)return existing.getString("@id");
        var record=Json.createObjectBuilder().add("@uuid","urn:uuid:"+operation).add("name",name).add("$_event_event_tag",Json.createArrayBuilder().add(Json.createObjectBuilder().add("tag","platform.disasterId").add("value",String.valueOf(disaster))).add(Json.createObjectBuilder().add("tag","platform.source").add("value",source))).build();
        persist(organization,"/event/event.s3json","event_event",record);
        var result=byUuid(organization,operation,"event_event");if(result==null)throw new WebApplicationException(503);return result.getString("@id");
    }
    private PeopleRecord record(String organization,JsonObject row){
        try {return new PeopleRecord(view(organization,row,null),UUID.fromString(row.getString("@uuid").replace("urn:uuid:","")));}
        catch(RuntimeException malformed){throw new WebApplicationException(502);}
    }
    private JsonObject view(String organization,JsonObject row,Integer disaster){
        String firstName=raw(row,"first_name");if(firstName==null||firstName.isBlank())throw new WebApplicationException(502);
        var value=Json.createObjectBuilder().add("id",id(row.getString("@id"))).add("firstName",firstName);
        nullable(value,"lastName",raw(row,"last_name"));nullable(value,"identificationRef",raw(row,"pe_label"));nullable(value,"birthDate",raw(row,"date_of_birth"));nullable(value,"locationId",reference(row,"location_id"));value.add("organizationId",organization);if(disaster==null)value.addNull("disasterId");else value.add("disasterId",disaster);
        String phone=null,email=null;for(var item:array(row,"$_pr_contact")){var contact=item.asJsonObject();if("SMS".equals(raw(contact,"contact_method"))&&phone==null)phone=raw(contact,"value");if("EMAIL".equals(raw(contact,"contact_method"))&&email==null)email=raw(contact,"value");}nullable(value,"phone",phone);nullable(value,"email",email);
        // Case, family and shelter links need their own event/realm verification in Eden.
        value.addNull("caseIds").addNull("caseStatusId").addNull("familyId").addNull("shelterId");
        String url=http.nativeUrl("/pr/person/"+id(row.getString("@id")));
        if(url==null)value.addNull("edenUrl");else value.add("edenUrl",url);
        return value.build();
    }
    private String id(String value){if(value==null||!value.matches("[1-9][0-9]{0,17}"))throw new WebApplicationException(502);return value;}
    private JsonArray array(JsonObject row,String key){return row.getJsonArray(key)==null?Json.createArrayBuilder().build():row.getJsonArray(key);}
    private String raw(JsonObject row,String key){var value=row.get(key);if(value==null||value==JsonValue.NULL)return null;if(value instanceof JsonObject object)value=object.get("@value");if(value==null||value==JsonValue.NULL)return null;return value instanceof JsonString text?text.getString():value.toString();}
    private String reference(JsonObject row,String field){var value=row.getJsonObject("$k_"+field);return value==null?null:id(value.getString("@id",null));}
    private void put(JsonObjectBuilder value,String key,String text){if(text!=null)value.add(key,text);}
    private void nullable(JsonObjectBuilder value,String key,String text){if(text==null)value.addNull(key);else value.add(key,text);}
}
