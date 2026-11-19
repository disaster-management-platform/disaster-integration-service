package platform.integration.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.json.JsonObject;
import java.util.UUID;
import platform.integration.business.CoordinationService;
import platform.integration.integration.http.IntegrationBudget;

@Path("/coordinacion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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
    @POST @Path("/tareas") @RolesAllowed("coordination.write")
    public Response createTask(@HeaderParam("Idempotency-Key") String key, JsonObject body) { return write(true,null,key,body); }
    @PUT @Path("/tareas/{id:[1-9][0-9]*}") @RolesAllowed("coordination.write")
    public Response updateTask(@PathParam("id") String id,@HeaderParam("Idempotency-Key") String key,JsonObject body) { return write(true,id,key,body); }
    @POST @Path("/actividades") @RolesAllowed("coordination.write")
    public Response createActivity(@HeaderParam("Idempotency-Key") String key, JsonObject body) { return write(false,null,key,body); }
    @PUT @Path("/actividades/{id:[1-9][0-9]*}") @RolesAllowed("coordination.write")
    public Response updateActivity(@PathParam("id") String id,@HeaderParam("Idempotency-Key") String key,JsonObject body) { return write(false,id,key,body); }
    private Response write(boolean task,String id,String key,JsonObject body) {
        budget.remainingMillis();
        UUID operation;
        try { operation=UUID.fromString(key); if (!operation.toString().equalsIgnoreCase(key) || operation.equals(new UUID(0,0))) throw new IllegalArgumentException(); }
        catch (RuntimeException invalid) { throw new BadRequestException(); }
        var result=service.write(identity.organization(),identity.actor(),identity.disasters(),task,id,operation,body);
        return Response.status(result.status()).entity(result.body()).build();
    }
    private Object list(Integer disaster, int page, int size, boolean tasks) {
        budget.remainingMillis(); identity.actor();
        return service.list(identity.organization(), identity.disasters(), disaster, page, size, tasks);
    }
}
