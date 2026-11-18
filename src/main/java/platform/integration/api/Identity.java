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

    public String issuer() { return require("iss"); }

    public void requireRole(String role) {
        if (token.getGroups() == null || !token.getGroups().contains(role)) throw new WebApplicationException(403);
    }

    public String organization() {
        return require("organization_id");
    }

    public java.util.Set<Integer> disasters(){
        var result=new java.util.HashSet<Integer>();Object value=token.getClaim("disaster_id");
        var values=value instanceof java.util.Collection<?> collection?collection:java.util.Collections.singleton(value);
        for(var item:values)try{int id=Integer.parseInt(item instanceof jakarta.json.JsonString text?text.getString():String.valueOf(item));if(id>0)result.add(id);}catch(NumberFormatException ignored){}
        return result;
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
