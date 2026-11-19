package platform.integration.business;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import platform.integration.domain.*;
import jakarta.ejb.EJB;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.persistence.IntegrationStore;
import java.util.*;
import java.io.StringReader;

/**
 * Caso de uso: consulta del directorio de organizaciones.
 * Stateless EJB — sin estado mutable en campos; pooling administrado por WildFly.
 * Aplica reglas de integración sin depender de protocolos ni estructuras de Eden.
 */
@Stateless
@jakarta.ejb.TransactionAttribute(jakarta.ejb.TransactionAttributeType.NOT_SUPPORTED)
public class OrganizationService {

    // Límites de paginación de integración
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 25;

    @Inject
    OrganizationPort port;
    @EJB IntegrationStore store;

    /**
     * Retorna la página de organizaciones autorizada para el consumidor.
     * Valida parámetros antes de delegar al adaptador.
     */
    public OrganizationPage listOrganizations(String query, Integer page, Integer size) {
        int p=page==null?0:page;
        int s=size==null?DEFAULT_PAGE_SIZE:size;
        if(p<0||s<1||s>MAX_PAGE_SIZE||(long)p*s>Integer.MAX_VALUE-s||query!=null&&query.length()>200)throw new jakarta.ws.rs.BadRequestException();
        String q=query==null||query.isBlank()?null:query.strip();
        return port.findOrganizations(q, p, s);
    }
    public OrganizationDto getOrganization(String organization,String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,17}")) throw new jakarta.ws.rs.BadRequestException();
        return port.detail(organization,id);
    }
    public OrganizationTypePage listOrganizationTypes(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0 || s < 1 || s > MAX_PAGE_SIZE || (long)p * s > Integer.MAX_VALUE - s)
            throw new jakarta.ws.rs.BadRequestException();
        return port.findOrganizationTypes(p, s);
    }
    public record Result(int status, OrganizationDto body) {}
    public Result write(String organization,String actor,String target,UUID request,JsonObject body){
        if(target!=null&&!target.matches("[1-9][0-9]{0,17}"))throw new jakarta.ws.rs.BadRequestException();
        var input=OrganizationInput.parse(body);
        var canonical=Json.createObjectBuilder();body.keySet().stream().sorted().forEach(k->canonical.add(k,body.get(k)));
        String digest=OperationIdentity.scoped("ORG",target==null?"":target,canonical.build().toString());
        var receipt=store.claimResource(OperationIdentity.key("organization",organization,request.toString()),organization,actor,"ORG",digest,null,target==null?null:"ORG/"+target);
        if(receipt.result!=null){
            if(receipt.status>=300)throw new WebApplicationException(receipt.status);
            try(var reader=Json.createReader(new StringReader(receipt.result))){return new Result(200,port.findOrganization(reader.readObject().getString("id")));}
        }
        boolean[] dispatched={receipt.dispatched};
        try{
            var view=port.write(organization,target,input,receipt.externalUuid,()->{boolean first=store.markDispatched(receipt.id,receipt.lease);dispatched[0]=true;return first;});
            int status=target==null?201:200;
            store.finish(receipt.id,receipt.lease,Json.createObjectBuilder().add("id",view.id()).build().toString(),status,null,null,null);
            return new Result(status,view);
        }catch(WebApplicationException failure){
            int status=failure.getResponse().getStatus();
            if(!dispatched[0]&&Set.of(400,403,404,409,422).contains(status))store.finish(receipt.id,receipt.lease,"{}",status,null,null,null);
            else store.release(receipt.id,receipt.lease);
            throw failure;
        }catch(RuntimeException failure){store.release(receipt.id,receipt.lease);throw failure;}
    }
}
