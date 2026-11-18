package platform.integration.api;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import platform.integration.integration.eden.EdenHttpClient;

import java.util.Map;

/**
 * Endpoints de diagnóstico del Integration Service.
 *
 * Liveness:  verifica el estado propio del servicio, independiente de Eden.
 * Readiness: comprueba de forma acotada si el adaptador Eden es alcanzable.
 *            No consulta el inventario completo; solo prueba conectividad mínima.
 */
@Path("/integracion/diagnostico")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class DiagnosticsResource {

    @Inject EdenHttpClient edenClient;

    @GET
    @Path("/live")
    public Object live() {
        return Map.of("status", "UP", "service", "integration-service");
    }

    @GET
    @Path("/ready")
    public Response ready() {
        boolean edenReachable = edenClient.probe();
        if (edenReachable) {
            return Response.ok(Map.of("status", "UP", "eden", "REACHABLE")).build();
        }
        return Response.status(503)
            .entity(Map.of("status", "DOWN", "eden", "UNREACHABLE"))
            .build();
    }
}
