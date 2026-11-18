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
        return visible(port.get(access.organization,id),link,true);
    }
    public JsonObject list(Access access,Integer disaster,String search,int page,int size){
        authorize(access,disaster);
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE-size||search!=null&&search.length()>200)throw new WebApplicationException(400);
        // Without a disaster filter, only uncorrelated registrations are visible. Native records
        // without a verified platform link stay in Eden until their context is established.
        var links=store.scopedLinks(access.organization,disaster,page*size,size+1);
        boolean more=links.size()>size;
        var selected=links.subList(0,Math.min(size,links.size()));
        var byId=new HashMap<String,PersonLink>();for(var link:selected)byId.put(link.personId,link);
        var records=port.list(access.organization,search,0,size,selected.stream().map(link->link.personId).toList());
        var result=Json.createArrayBuilder();var seen=new HashSet<String>();
        for(var record:records){String id=record.person().getString("id");var link=byId.get(id);
            if(link==null||!seen.add(id))throw new WebApplicationException(502);
            result.add(visible(record,link,false));}
        return Json.createObjectBuilder().add("items",result).add("page",page).add("pageSize",size).add("hasMore",more).addNull("total").build();
    }
    public JsonObject summary(Access access,Integer disaster){
        authorize(access,disaster);
        var links=store.scopedLinks(access.organization,disaster,0,101);
        var value=Json.createObjectBuilder().addNull("familyCount").addNull("openCases").addNull("shelteredCount").addNull("zones").addNull("needs");
        if(links.isEmpty())value.add("peopleCount",0);
        else if(links.size()>100)value.addNull("peopleCount");
        else {
            var records=port.list(access.organization,null,0,links.size(),links.stream().map(link->link.personId).toList());
            var expected=new HashMap<String,UUID>();for(var link:links)expected.put(link.personId,link.externalUuid);
            var seen=new HashSet<String>();
            for(var record:records){String id=record.person().getString("id");
                if(!Objects.equals(expected.get(id),record.identity())||!seen.add(id))throw new WebApplicationException(502);}
            if(seen.size()==links.size())value.add("peopleCount",seen.size());else value.addNull("peopleCount");
        }
        if(disaster==null)value.addNull("disasterId");else value.add("disasterId",disaster);return value.build();
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
            boolean first=store.markDispatched(receipt.id,receipt.lease);
            PeopleRecord result=person==null?port.create(access.organization,receipt.externalUuid,parsed):port.update(access.organization,person,parsed,receipt.externalUuid,expectedIdentity,first);
            if(person!=null&&(!person.equals(result.person().getString("id"))||!Objects.equals(expectedIdentity,result.identity())))throw new WebApplicationException(502);
            if(person==null&&!receipt.externalUuid.equals(result.identity()))throw new WebApplicationException(502);
            var response=Json.createObjectBuilder(result.person());if(parsed.disasterId()==null)response.addNull("disasterId");else response.add("disasterId",parsed.disasterId());var body=response.build();
            int status=person==null?201:200;store.finish(receipt.id,receipt.lease,Json.createObjectBuilder().add("id",body.getString("id")).build().toString(),status,body.getString("id"),event,result.identity());return new Result(status,body);
        }catch(RuntimeException failure){store.release(receipt.id,receipt.lease);throw failure;}
    }
}
