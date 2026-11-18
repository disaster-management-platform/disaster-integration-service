package platform.integration.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import platform.integration.business.OrganizationService;
import platform.integration.domain.OrganizationPage;

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
        return service.getOrganization(id);
    }
}
