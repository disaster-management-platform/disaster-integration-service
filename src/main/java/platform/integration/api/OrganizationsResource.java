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
        @QueryParam("q")        String query,
        @QueryParam("query")    String queryAlt,
        @QueryParam("pagina")   Integer page,
        @QueryParam("page")     Integer pageAlt,
        @QueryParam("tamanio")  Integer size,
        @QueryParam("pageSize") Integer sizeAlt,
        @QueryParam("limit")    Integer limitAlt
    ) {
        // Verifica identidad antes de cualquier operación de integración
        identity.actor();
        identity.organization();

        String q = (query != null && !query.isBlank()) ? query : queryAlt;
        Integer p = (page != null) ? page : pageAlt;
        Integer s = (size != null) ? size : (sizeAlt != null ? sizeAlt : limitAlt);

        OrganizationPage result = service.listOrganizations(q, p, s);
        return Response.ok(result)
            .header("X-Total-Count", result.total())
            .header("X-Page", result.page())
            .header("X-Page-Size", result.pageSize())
            .build();
    }
}
