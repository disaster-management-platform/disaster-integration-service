package platform.integration.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import platform.integration.business.ExternalReportService;
import platform.integration.domain.ExternalReportSource;
import platform.integration.integration.http.IntegrationBudget;

@Path("/integracion/reportes/{category}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"integration.monitoring", "integration.gis", "integration.hospital", "integration.administrative", "integration.financial"})
public class ExternalReportsResource {
    @Inject Identity identity;
    @Inject IntegrationBudget budget;
    @EJB ExternalReportService service;
    @POST
    public Response receive(@PathParam("category") String category, JsonObject input) {
        budget.remainingMillis();
        if (!ExternalReportSource.CATEGORIES.contains(category)) throw new NotFoundException();
        identity.requireRole("integration." + category);
        var receipt = service.receive(category, identity.issuer(), identity.organization(), identity.actor(), input);
        return Response.status(receipt.status()).entity(receipt.body()).build();
    }
}
