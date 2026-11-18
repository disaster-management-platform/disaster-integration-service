package platform.integration.api;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Extrae y valida la identidad del JWT de la plataforma.
 * Independiente de la autenticación técnica contra Eden.
 */
@RequestScoped
public class Identity {

    @Inject JsonWebToken token;

    public String actor() {
        return require("sub");
    }

    public String organization() {
        return require("organization_id");
    }

    private String require(String claim) {
        Object value = token.getClaim(claim);
        if (value instanceof jakarta.json.JsonString js) value = js.getString();
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200) {
            throw new WebApplicationException(403);
        }
        return text;
    }
}
