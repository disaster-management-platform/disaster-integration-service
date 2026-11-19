package platform.integration.persistence;
import jakarta.ejb.*;
import jakarta.persistence.*;
import jakarta.ws.rs.WebApplicationException;
import java.util.*;
import java.time.*;
@Stateless @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class IntegrationStore {
    @PersistenceContext(unitName="integration") EntityManager em;
    public Receipt claim(UUID id,String organization,String actor,String kind,String command,Integer disaster,String targetPerson) {
        return claim(id, organization, actor, kind, command, disaster, targetPerson, null);
    }
    public Receipt claimResource(UUID id,String organization,String actor,String kind,String command,Integer disaster,String targetResource) {
        return claim(id, organization, actor, kind, command, disaster, null, targetResource);
    }
    private Receipt claim(UUID id,String organization,String actor,String kind,String command,Integer disaster,String targetPerson,String targetResource) {
        if(targetPerson!=null){
            var link=em.find(PersonLink.class,organization+"/"+targetPerson,LockModeType.PESSIMISTIC_WRITE);
            if(link==null)throw new WebApplicationException(404);
            if(!Objects.equals(link.disasterId,disaster))throw new WebApplicationException(409);
            var outstanding=em.createQuery("select r.id from Receipt r where r.organization=:org and r.targetPerson=:target and r.result is null and r.id<>:id",UUID.class)
                .setParameter("org",organization).setParameter("target",targetPerson).setParameter("id",id).setMaxResults(1).getResultList();
            if(!outstanding.isEmpty())throw new WebApplicationException(409);
        }
        em.createNativeQuery("INSERT INTO integration.receipts(id,organization,actor,kind,command,external_uuid,disaster_id,target_person,target_resource) VALUES(:id,:org,:actor,:kind,:command,:id,:disaster,:target,:resource) ON CONFLICT DO NOTHING")
            .setParameter("id",id).setParameter("org",organization).setParameter("actor",actor).setParameter("kind",kind).setParameter("command",command).setParameter("disaster",disaster).setParameter("target",targetPerson).setParameter("resource",targetResource).executeUpdate();
        Receipt row=em.find(Receipt.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null||!row.organization.equals(organization)||!row.command.equals(command)||!row.kind.equals(kind)||(!kind.equals("EVENT")&&!row.actor.equals(actor))||!Objects.equals(row.targetPerson,targetPerson)||!Objects.equals(row.targetResource,targetResource)||!Objects.equals(row.disasterId,disaster))throw new WebApplicationException(409);
        if(row.result!=null)return row;
        if(row.leaseUntil!=null&&row.leaseUntil.isAfter(OffsetDateTime.now(ZoneOffset.UTC)))throw new WebApplicationException(503);
        row.lease=UUID.randomUUID();row.leaseUntil=OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(45);em.flush();return row;
    }
    public boolean markDispatched(UUID id,UUID lease) {
        var row=em.find(Receipt.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row==null||!Objects.equals(row.lease,lease))throw new WebApplicationException(503);
        boolean first=!row.dispatched;row.dispatched=true;return first;
    }
    public void finish(UUID id,UUID lease,String result,int status,String personId,String eventId,UUID personUuid) {
        Receipt row=em.find(Receipt.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(!Objects.equals(row.lease,lease))throw new WebApplicationException(503);
        row.result=result;row.status=status;row.lease=null;row.leaseUntil=null;
        if(personId!=null&&status<300){
            String key=row.organization+"/"+personId;
            var link=em.find(PersonLink.class,key);
            if(link==null){
                if(!row.kind.equals("PERSON")||personUuid==null)throw new WebApplicationException(409);
                link=new PersonLink();link.id=key;link.organization=row.organization;link.personId=personId;
                link.externalUuid=personUuid;link.disasterId=row.disasterId;link.eventId=eventId;em.persist(link);
            }else if(!Objects.equals(link.externalUuid,personUuid)||!Objects.equals(link.disasterId,row.disasterId)||!Objects.equals(link.eventId,eventId))throw new WebApplicationException(409);
        }
    }
    public void release(UUID id,UUID lease){
        var row=em.find(Receipt.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(Objects.equals(row.lease,lease)){row.leaseUntil=null;row.lease=null;}
    }
    public Receipt eventReceipt(String organization,int disaster){
        var rows=em.createQuery("select r from Receipt r where r.organization=:org and r.kind='EVENT' and r.disasterId=:disaster",Receipt.class)
            .setParameter("org",organization).setParameter("disaster",disaster).setMaxResults(2).getResultList();
        if(rows.size()>1)throw new WebApplicationException(409);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<PersonLink> links(String organization,List<String> ids) {
        if(ids.isEmpty())return List.of();
        return em.createQuery("select p from PersonLink p where p.organization=:org and p.personId in :ids",PersonLink.class).setParameter("org",organization).setParameter("ids",ids).getResultList();
    }
    public List<PersonLink> scopedLinks(String organization,Integer disaster,int offset,int limit) {
        String condition=disaster==null?"p.disasterId is null":"p.disasterId=:disaster";
        var query=em.createQuery("select p from PersonLink p where p.organization=:org and "+condition+" order by p.personId",PersonLink.class)
            .setParameter("org",organization).setFirstResult(offset).setMaxResults(limit);
        if(disaster!=null)query.setParameter("disaster",disaster);
        return query.getResultList();
    }
}
