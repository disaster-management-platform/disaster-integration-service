package platform.integration.api;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import platform.integration.business.PeopleService;
import java.util.UUID;
@Path("/personas-afectadas") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"people.read","people.write"})
public class PeopleResource {
    @Inject Identity identity;
    @Inject platform.integration.integration.http.IntegrationBudget budget;
    @EJB PeopleService service;
    private PeopleService.Access access(){budget.remainingMillis();return new PeopleService.Access(identity.organization(),identity.actor(),identity.disasters());}
    @GET public Object list(@QueryParam("disasterId") Integer disaster,@QueryParam("search") String search,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("size") @DefaultValue("25") int size){return service.list(access(),disaster,search,page,size);}
    @GET @Path("/resumen") public Object summary(@QueryParam("disasterId") Integer disaster){return service.summary(access(),disaster);}
    @GET @Path("/{id:[1-9][0-9]*}") public Object get(@PathParam("id") String id){return service.get(access(),id);}
    @POST @RolesAllowed("people.write") public Response create(@HeaderParam("Idempotency-Key") String key,JsonObject input){var access=access();var result=service.write(access,null,uuid(key),input);return Response.status(result.status()).entity(result.body()).build();}
    @PUT @Path("/{id:[1-9][0-9]*}") @RolesAllowed("people.write") public Response update(@PathParam("id") String id,@HeaderParam("Idempotency-Key") String key,JsonObject input){var access=access();var result=service.write(access,id,uuid(key),input);return Response.status(result.status()).entity(result.body()).build();}
    @POST @Path("/{id:[1-9][0-9]*}/vincular") @RolesAllowed("people.write")
    public Object bind(@PathParam("id") String id,JsonObject input){
        var access=access();
        if(input==null||!java.util.Set.of("disasterId").containsAll(input.keySet()))throw new BadRequestException();
        Integer disaster=null;
        if(input.containsKey("disasterId")&&!input.isNull("disasterId"))try{disaster=input.getJsonNumber("disasterId").intValueExact();if(disaster<=0)throw new BadRequestException();}catch(RuntimeException invalid){throw new BadRequestException();}
        return service.bind(access,id,disaster);
    }
    private UUID uuid(String value){try{var id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value)||id.equals(new UUID(0,0)))throw new BadRequestException();return id;}catch(RuntimeException failure){throw new BadRequestException();}}
}
