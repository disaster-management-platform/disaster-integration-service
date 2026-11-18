package platform.integration.integration.eden;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.*;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Adaptador Eden para organizaciones — implementa OrganizationPort (puerto de salida).
 *
 * Anti-Corruption Layer: encapsula todo conocimiento de:
 * - La ruta real del recurso ORG de Eden.
 * - El formato web2py de la respuesta JSON.
 * - Los nombres de campos nativos de Eden.
 * - Los identificadores externos (opacos y estables).
 * - Los mecanismos de paginación de Eden.
 *
 * Ningún modelo nativo de Eden se propaga más allá de este adaptador.
 */
@ApplicationScoped
public class OrganizationAdapter implements OrganizationPort {

    private static final Logger LOG = Logger.getLogger(OrganizationAdapter.class.getName());

    // Ruta del módulo ORG en Eden — conocida únicamente por este adaptador
    private static final String ORG_PATH = "/org/organisation.json";

    // Nombre del campo de tipo en la respuesta de Eden (join implícito web2py)
    private static final String EDEN_TYPE_FIELD = "org_organisation_organisation_type.organisation_type_id";

    @Inject
    EdenHttpClient http;

    @Override
    public OrganizationPage findOrganizations(String query, int page, int size) {
        String path = buildPath(query, page, size);
        String body = http.get(path);
        return translate(body, page, size);
    }

    // ── Construcción de la solicitud Eden ──────────────────────────────────

    private String buildPath(String query, int page, int size) {
        // Eden usa start (offset) y limit (cantidad)
        int start = page * size;
        StringBuilder sb = new StringBuilder(ORG_PATH)
            .append("?start=").append(start)
            .append("&limit=").append(size);
        if (query != null) {
            sb.append("&organisation.name__contains=")
              .append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ── Traducción ACL: respuesta Eden → DTO canónico ─────────────────────

    private OrganizationPage translate(String body, int page, int size) {
        if (body == null || body.isBlank()) {
            throw new WebApplicationException(502);
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            // Eden devuelve un array JSON — web2py nunca envuelve en objeto aquí
            JsonArray array = reader.readArray();
            List<OrganizationDto> items = new ArrayList<>(array.size());
            for (JsonValue element : array) {
                if (element instanceof JsonObject obj) {
                    items.add(mapOrganization(obj));
                }
            }
            // Eden no incluye el total en la respuesta de lista;
            // se determina con la cantidad retornada y la posición de página
            int total = resolveTotal(items.size(), page, size);
            return new OrganizationPage(items, page, size, total);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("Eden response could not be parsed: " + e.getMessage());
            throw new WebApplicationException(502);
        }
    }

    /**
     * Mapea un registro nativo de Eden al DTO canónico.
     * Los identificadores son valores opacos — nunca se usan nombres como ID.
     * Los campos opcionales son null cuando Eden no los provee.
     */
    private OrganizationDto mapOrganization(JsonObject obj) {
        String id          = stringOrNull(obj, "id");
        String name        = stringOrNull(obj, "name");

        if (id == null || name == null || name.isBlank()) {
            throw new WebApplicationException(502);
        }

        // typeId: Eden expone el id del tipo a través del join implícito
        String rawTypeId   = stringOrNull(obj, EDEN_TYPE_FIELD);
        String typeId      = isNullOrDash(rawTypeId) ? null : rawTypeId;

        String acronym     = emptyToNull(stringOrNull(obj, "acronym"));
        String country     = emptyToNull(stringOrNull(obj, "country"));
        String locationId  = stringOrNull(obj, "pe_id");   // pe_id es el identificador de entidad física
        String phone       = emptyToNull(stringOrNull(obj, "phone"));
        String email       = emptyToNull(stringOrNull(obj, "email"));
        String description = emptyToNull(stringOrNull(obj, "comments"));

        return new OrganizationDto(id, name, typeId, acronym, country, locationId, phone, email, description);
    }

    /**
     * Inferencia conservadora del total:
     * - Si la página devolvió menos registros que el tamaño solicitado, es la última página.
     * - Sino, reportamos al menos (page+1)*size (hay más páginas).
     */
    private int resolveTotal(int count, int page, int size) {
        if (count < size) {
            return page * size + count;
        }
        return (page + 1) * size; // hay al menos una página más; total exacto no disponible
    }

    // ── Utilidades de traducción ───────────────────────────────────────────

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.containsKey(key) || obj.isNull(key)) return null;
        JsonValue v = obj.get(key);
        return switch (v.getValueType()) {
            case STRING -> ((JsonString) v).getString();
            case NUMBER -> obj.getJsonNumber(key).toString();
            default -> null;
        };
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean isNullOrDash(String s) {
        return s == null || s.isBlank() || "-".equals(s.trim());
    }
}
