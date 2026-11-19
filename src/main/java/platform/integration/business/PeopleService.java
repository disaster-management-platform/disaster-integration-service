package platform.integration.business;
import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.*;
import platform.integration.persistence.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
@Stateless @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class PeopleService {
    @Inject PeoplePort port;
    @EJB DisasterContextService context;
    @EJB IntegrationStore store;
    public record Access(String organization,String actor,Set<Integer> disasters){public void require(Integer id){if(id!=null&&!disasters.contains(id))throw new WebApplicationException(403);}}
    public record Result(int status,JsonObject body){}
    private JsonObject object(String body){
        try(var reader=Json.createReader(new StringReader(body))){return reader.readObject();}
        catch(RuntimeException malformed){throw new WebApplicationException(502);}
    }
    private UUID key(String organization,String value){return UUID.nameUUIDFromBytes((organization+"/"+value).getBytes(StandardCharsets.UTF_8));}
    private void authorize(Access access,Integer id){
        access.require(id);
        if(id!=null)context.authorize(access.organization,access.disasters,id);
    }
    private PersonLink requireLink(Access access,String id){
        if(id==null||!id.matches("[1-9][0-9]{0,17}"))throw new WebApplicationException(400);
        var links=store.links(access.organization,List.of(id));
        if(links.size()!=1)throw new WebApplicationException(404);
        var link=links.getFirst();
        if(link.disasterId!=null&&!access.disasters.contains(link.disasterId))throw new WebApplicationException(404);
        authorize(access,link.disasterId);
        return link;
    }
    private JsonObject visible(PeopleRecord record,PersonLink link,boolean detail){
        if(!link.personId.equals(record.person().getString("id"))||!Objects.equals(link.externalUuid,record.identity()))throw new WebApplicationException(502);
        var value=Json.createObjectBuilder(record.person());
        if(link.disasterId==null)value.addNull("disasterId");else value.add("disasterId",link.disasterId);
        if(!detail)for(String field:List.of("phone","email","familyId","caseIds","shelterId","edenUrl"))value.remove(field);
        return value.build();
    }
    public JsonObject get(Access access,String id){
        var link=requireLink(access,id);
        var value=Json.createObjectBuilder(visible(port.get(access.organization,id),link,true));
        var relations=port.relationships(access.organization,List.of(id)).getJsonObject(id);
        relations.forEach(value::add);
        return value.build();
    }
    public JsonObject list(Access access,Integer disaster,String search,int page,int size){
        authorize(access,disaster);
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE-size||search!=null&&search.length()>200)throw new WebApplicationException(400);
        var result=Json.createArrayBuilder();
        long matched=0;long offset=(long)page*size;
        for(int scan=0;;scan+=100){
            var links=store.scopedLinks(access.organization,disaster,scan,100);
            if(links.isEmpty())break;
            var byId=new HashMap<String,PersonLink>();for(var link:links)byId.put(link.personId,link);
            var records=port.list(access.organization,null,0,100,links.stream().map(link->link.personId).toList());
            if(records.size()!=links.size())throw new WebApplicationException(503);
            var seen=new HashSet<String>();
            for(var record:records){
                String id=record.person().getString("id");var link=byId.get(id);
                if(link==null||!seen.add(id))throw new WebApplicationException(502);
                var person=visible(record,link,false);
                String text=person.getString("firstName","")+" "+person.getString("lastName","")+" "+person.getString("identificationRef","");
                if(search==null||text.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))){
                    if(matched>=offset&&matched<offset+size)result.add(person);
                    matched++;
                }
            }
            if(links.size()<100)break;
        }
        return Json.createObjectBuilder().add("items",result).add("page",page).add("pageSize",size).add("hasMore",matched>offset+size).add("total",matched).build();
    }
    public JsonObject summary(Access access,Integer disaster){
        authorize(access,disaster);
        long people=0;var families=new HashSet<String>();long cases=0,sheltered=0;
        var zones=new TreeMap<String,Long>();var needs=new TreeMap<String,Long>();
        for(int scan=0;;scan+=100){
            var links=store.scopedLinks(access.organization,disaster,scan,100);if(links.isEmpty())break;
            var records=port.list(access.organization,null,0,100,links.stream().map(link->link.personId).toList());
            var expected=new HashMap<String,PersonLink>();for(var link:links)expected.put(link.personId,link);
            if(records.size()!=links.size())throw new WebApplicationException(503);
            var seen=new HashSet<String>();
            for(var record:records){var link=expected.get(record.person().getString("id"));if(link==null||!seen.add(link.personId))throw new WebApplicationException(502);visible(record,link,false);
                String zone=record.person().getString("locationId",null);if(zone!=null)zones.merge(zone,1L,Long::sum);}
            var relations=port.relationships(access.organization,links.stream().map(link->link.personId).toList());
            for(String id:relations.keySet()){
                var relation=relations.getJsonObject(id);for(var family:relation.getJsonArray("familyIds"))families.add(((JsonString)family).getString());
                cases+=relation.getInt("openCases");if(!relation.getJsonArray("shelterIds").isEmpty())sheltered++;
                for(var need:relation.getJsonArray("needs")){String key=need.asJsonObject().getString("needId",null);if(key!=null)needs.merge(key,1L,Long::sum);}
            }
            people+=links.size();if(links.size()<100)break;
        }
        var zoneValues=Json.createArrayBuilder();zones.forEach((id,count)->zoneValues.add(Json.createObjectBuilder().add("locationId",id).add("count",count)));
        var needValues=Json.createArrayBuilder();needs.forEach((id,count)->needValues.add(Json.createObjectBuilder().add("needId",id).add("count",count)));
        var value=Json.createObjectBuilder().add("peopleCount",people).add("familyCount",families.size()).add("openCases",cases).add("shelteredCount",sheltered).add("zones",zoneValues).add("needs",needValues);
        if(disaster==null)value.addNull("disasterId");else value.add("disasterId",disaster);return value.build();
    }
    public JsonObject bind(Access access,String id,Integer disaster){
        authorize(access,disaster);
        var record=port.get(access.organization,id);
        String event=disaster==null?null:context.correlate(access.organization,access.actor,access.disasters,disaster);
        store.bindPerson(access.organization,id,record.identity(),disaster,event);
        return get(access,id);
    }
    public Result write(Access access,String person,UUID request,JsonObject input){
        PeopleInput parsed=PeopleInput.parse(input);authorize(access,parsed.disasterId());
        if(parsed.organizationId()!=null&&!access.organization.equals(parsed.organizationId()))throw new WebApplicationException(403);
        UUID expectedIdentity=null;
        if(person!=null){
            var previous=requireLink(access,person);
            expectedIdentity=previous.externalUuid;
            Integer linked=previous.disasterId;
            if(!Objects.equals(linked,parsed.disasterId()))throw new WebApplicationException(409);
        }
        var canonical=Json.createObjectBuilder();input.keySet().stream().sorted().forEach(name->canonical.add(name,input.get(name)));
        String command=Json.createObjectBuilder().add("operation",person==null?"CREATE":"UPDATE").add("personId",person==null?"":person).add("data",canonical).build().toString();
        var receipt=store.claim(key(access.organization,request.toString()),access.organization,access.actor,person==null?"PERSON":"UPDATE",OperationIdentity.digest(command),parsed.disasterId(),person);
        if(receipt.result!=null){
            if(receipt.status>=300)return new Result(receipt.status,object(receipt.result));
            String previousId=object(receipt.result).getString("id");
            // Store only the identifier, not a second copy of sensitive personal details.
            // Replay returns the current authorized representation, not a historical PII snapshot.
            return new Result(200,get(access,previousId));
        }
        try{
            String event=parsed.disasterId()==null?null:context.correlate(access.organization,access.actor,access.disasters,parsed.disasterId());
            PeopleRecord result=person==null?port.create(access.organization,receipt.externalUuid,parsed):port.update(access.organization,person,parsed,receipt.externalUuid,expectedIdentity,()->store.markDispatched(receipt.id,receipt.lease));
            if(person!=null&&(!person.equals(result.person().getString("id"))||!Objects.equals(expectedIdentity,result.identity())))throw new WebApplicationException(502);
            if(person==null&&!receipt.externalUuid.equals(result.identity()))throw new WebApplicationException(502);
            var response=Json.createObjectBuilder(result.person());if(parsed.disasterId()==null)response.addNull("disasterId");else response.add("disasterId",parsed.disasterId());var body=response.build();
            int status=person==null?201:200;store.finish(receipt.id,receipt.lease,Json.createObjectBuilder().add("id",body.getString("id")).build().toString(),status,body.getString("id"),event,result.identity());return new Result(status,body);
        }catch(WebApplicationException failure){
            int status=failure.getResponse().getStatus();
            if(Set.of(400,403,404,409,422).contains(status))store.finish(receipt.id,receipt.lease,Json.createObjectBuilder().add("error","Native write rejected").build().toString(),status,null,null,null);
            else store.release(receipt.id,receipt.lease);
            throw failure;
        }catch(RuntimeException failure){store.release(receipt.id,receipt.lease);throw failure;}
    }
}
