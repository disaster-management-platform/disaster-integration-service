package platform.integration.integration.eden;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Cliente HTTP reutilizable para las APIs nativas de Eden.
 *
 * Responsabilidades:
 * - Mantener conexiones reutilizables (HttpClient singleton por instancia CDI).
 * - Configurar timeouts de conexión y respuesta.
 * - Gestionar autenticación Basic de la identidad técnica restringida.
 * - Tratar fallos de red y cancelaciones.
 * - No mantener transacciones JTA durante operaciones de red.
 * - No registrar credenciales, cookies, tokens ni datos sensibles.
 */
@ApplicationScoped
public class EdenHttpClient {

    private static final Logger LOG = Logger.getLogger(EdenHttpClient.class.getName());

    static final int CONNECT_TIMEOUT_S  = 5;
    static final int REQUEST_TIMEOUT_S  = 10;
    static final long MAX_RESPONSE_BYTES = 4 * 1024 * 1024; // 4 MB

    private HttpClient client;
    private String authorizationHeader;
    private String baseUrl;

    @PostConstruct
    void start() {
        baseUrl = setting("EDEN_BASE_URL");           // e.g. http://127.0.0.1:8001/eden_default
        String user     = setting("EDEN_TECH_USER");  // identidad técnica restringida
        String password = setting("EDEN_TECH_PASSWORD");
        authorizationHeader = "Basic " +
            Base64.getEncoder().encodeToString((user + ":" + password).getBytes());

        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @PreDestroy
    void stop() {
        client.close();
    }

    /**
     * Ejecuta una solicitud GET autenticada.
     * @param path ruta relativa, ya codificada (ej. /org/organisation.json?pagesize=25)
     * @return cuerpo de la respuesta como String
     * @throws WebApplicationException 502 respuesta inesperada del proveedor,
     *         503 dependencia indisponible, 504 timeout
     */
    public String get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                // Error de autenticación técnica — no confundir con el JWT del consumidor
                LOG.warning("Eden technical identity rejected (HTTP " + response.statusCode() + ")");
                throw new WebApplicationException(503);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warning("Unexpected Eden HTTP status: " + response.statusCode());
                throw new WebApplicationException(502);
            }
            if (response.body() != null && response.body().length() > MAX_RESPONSE_BYTES) {
                LOG.warning("Eden response exceeds size limit");
                throw new WebApplicationException(502);
            }
            return response.body();

        } catch (java.net.http.HttpTimeoutException e) {
            throw new WebApplicationException(504);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebApplicationException(503);
        } catch (IOException e) {
            throw new WebApplicationException(503);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Unexpected error calling Eden: " + e.getClass().getSimpleName());
            throw new WebApplicationException(502);
        }
    }

    /**
     * Verifica conectividad acotada con Eden mediante HEAD (o GET a la raíz).
     * No consulta el inventario completo; solo comprueba que el proveedor responde.
     */
    public boolean probe() {
        try {
            HttpRequest probe = HttpRequest.newBuilder(
                    URI.create(baseUrl + "/org/organisation.json?limit=1"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Authorization", authorizationHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<Void> r = client.send(probe, HttpResponse.BodyHandlers.discarding());
            return r.statusCode() >= 200 && r.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private String setting(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new WebApplicationException("Required env var not set: " + key, 503);
        }
        return value;
    }
}
