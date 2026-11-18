package platform.integration.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import platform.integration.business.CoordinationService;
import platform.integration.integration.http.IntegrationBudget;

@Path("/coordinacion")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"coordination.read", "coordination.write"})
public class CoordinationResource {
    @EJB CoordinationService service;
    @Inject Identity identity;
    @Inject IntegrationBudget budget;
    @GET @Path("/tareas")
    public Object tasks(@QueryParam("disasterId") Integer disaster, @DefaultValue("0") @QueryParam("page") int page, @DefaultValue("25") @QueryParam("size") int size) {
        return list(disaster, page, size, true);
    }
    @GET @Path("/actividades")
    public Object activities(@QueryParam("disasterId") Integer disaster, @DefaultValue("0") @QueryParam("page") int page, @DefaultValue("25") @QueryParam("size") int size) {
        return list(disaster, page, size, false);
    }
    private Object list(Integer disaster, int page, int size, boolean tasks) {
        budget.remainingMillis(); identity.actor();
        return service.list(identity.organization(), identity.disasters(), disaster, page, size, tasks);
    }
}
