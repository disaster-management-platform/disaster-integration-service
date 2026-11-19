package platform.integration.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import platform.integration.business.OrganizationService;
import platform.integration.domain.OrganizationPage;
import jakarta.json.JsonObject;
import java.util.UUID;
import platform.integration.integration.http.IntegrationBudget;

/**
 * Recurso REST — adaptador de entrada para el directorio de organizaciones.
 * Valida la solicitud, verifica permisos y delega a la capa de negocio.
 * No contiene lógica de comunicación específica de Eden.
 */
@Path("/organizaciones")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"org.read", "org.admin", "platform.admin"})
public class OrganizationsResource {

    @EJB OrganizationService service;
    @Inject Identity identity;
    @Inject IntegrationBudget budget;

    @GET
    public Response list(
        @QueryParam("q") String query,
        @QueryParam("pagina") Integer page,
        @QueryParam("tamanio") Integer size
    ) {
        // Verifica identidad antes de cualquier operación de integración
        identity.actor();
        identity.organization();

        OrganizationPage result=service.listOrganizations(query,page,size);
        return Response.ok(result)
            .header("X-Page", result.page())
            .header("X-Page-Size", result.pageSize())
            .build();
    }
    @GET
    @Path("/tipos")
    public Object types(@QueryParam("pagina") Integer page, @QueryParam("tamanio") Integer size) {
        identity.actor();
        identity.organization();
        return service.listOrganizationTypes(page, size);
    }

    @GET
    @Path("/{id:[1-9][0-9]*}")
    public Object detail(@PathParam("id") String id) {
        identity.actor();
        identity.organization();
        return service.getOrganization(identity.organization(),id);
    }
    @POST @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed("org.create")
    public Response create(@HeaderParam("Idempotency-Key") String key,JsonObject body){return write(null,key,body);}
    @PUT @Path("/{id:[1-9][0-9]*}") @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed("org.admin")
    public Response update(@PathParam("id") String id,@HeaderParam("Idempotency-Key") String key,JsonObject body){return write(id,key,body);}
    private Response write(String target,String key,JsonObject body){
        budget.remainingMillis();UUID operation;
        try{operation=UUID.fromString(key);if(!operation.toString().equalsIgnoreCase(key)||operation.equals(new UUID(0,0)))throw new IllegalArgumentException();}catch(RuntimeException e){throw new BadRequestException();}
        var result=service.write(identity.organization(),identity.actor(),target,operation,body);
        return Response.status(result.status()).entity(result.body()).build();
    }
}
