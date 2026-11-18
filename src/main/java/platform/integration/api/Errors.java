package platform.integration.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.Map;

/**
 * Mapea excepciones a respuestas HTTP consistentes.
 * No expone trazas internas, credenciales ni respuestas del proveedor.
 */
@Provider
public class Errors implements ExceptionMapper<Exception> {

    public Response toResponse(Exception error) {
        int status = resolve(error);
        String detail = switch (status) {
            case 400 -> "Solicitud inválida";
            case 401 -> "Autenticación requerida";
            case 403 -> "Acceso insuficiente";
            case 409 -> "Conflicto de identidad";
            case 422 -> "Regla de negocio no satisfecha";
            case 413 -> "Solicitud demasiado grande";
            case 404 -> "Recurso inexistente o no visible";
            case 502 -> "Respuesta externa inválida";
            case 503 -> "Dependencia temporalmente indisponible";
            case 504 -> "Tiempo de espera externo agotado";
            default  -> "Error interno";
        };
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("status", status, "detail", detail))
            .build();
    }

    static int resolve(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebApplicationException web) {
                return web.getResponse().getStatus();
            }
            if (cause instanceof java.net.http.HttpTimeoutException) return 504;
            if (cause instanceof java.net.ConnectException)          return 503;
            if (cause instanceof java.io.IOException)                return 503;
            if (cause instanceof InterruptedException)               return 503;
        }
        return 500;
    }
}
