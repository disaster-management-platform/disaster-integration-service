package platform.integration.integration.eden;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
public class EdenDelegations {
    private JsonObject organizations;

    @PostConstruct
    void load() {
        String location = System.getenv("EDEN_DELEGATIONS_FILE");
        if (location == null || location.isBlank()) {
            organizations = Json.createObjectBuilder().build();
            return;
        }
        try (var reader = Json.createReader(Files.newBufferedReader(Path.of(location)))) {
            organizations = reader.readObject();
            var usedOrganizations = new java.util.HashSet<String>();
            var usedUsers = new java.util.HashSet<String>();
            for (String organization : organizations.keySet()) {
                var entry = organizations.getJsonObject(organization);
                if (entry == null || organization.isBlank() || organization.length() > 200 ||
                    !entry.getString("edenOrganizationId", "").matches("[1-9][0-9]*") ||
                    entry.getString("username", "").isBlank() ||
                    entry.getString("password", "").isBlank() ||
                    !usedOrganizations.add(entry.getString("edenOrganizationId")) ||
                    !usedUsers.add(entry.getString("username").toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalStateException("Invalid Eden delegation configuration");
                }
                if (entry.containsKey("coordination")) {
                    var coordination = entry.getJsonObject("coordination");
                    if (coordination == null || coordination.getString("username", "").isBlank() ||
                        coordination.getString("password", "").isBlank() ||
                        !usedUsers.add(coordination.getString("username").toLowerCase(java.util.Locale.ROOT)))
                        throw new IllegalStateException("Invalid coordination delegation");
                }
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("Eden delegation configuration unavailable or invalid");
        }
    }

    public String authorization(String organization) {
        if (organization == null) throw new WebApplicationException(403);
        var entry = organizations.getJsonObject(organization);
        if (entry == null) throw new WebApplicationException(403);
        return basic(entry);
    }

    public String coordinationAuthorization(String organization) {
        if (organization == null) throw new WebApplicationException(403);
        var entry = organizations.getJsonObject(organization);
        if (entry == null || !entry.containsKey("coordination")) throw new WebApplicationException(403);
        return basic(entry.getJsonObject("coordination"));
    }
    private static String basic(JsonObject entry) {
        return "Basic " + Base64.getEncoder().encodeToString((entry.getString("username") + ":" +
            entry.getString("password")).getBytes(StandardCharsets.UTF_8));
    }
    public boolean represents(String organization,String nativeId){
        var entry=organizations.getJsonObject(organization);
        return entry!=null&&nativeId.equals(entry.getString("edenOrganizationId"));
    }
    public String organizationCreator() {
        String user=System.getenv("EDEN_ORG_CREATE_USER"),password=System.getenv("EDEN_ORG_CREATE_PASSWORD");
        if(user==null||user.isBlank()||password==null||password.isBlank()||user.equalsIgnoreCase(System.getenv("EDEN_TECH_USER")))throw new WebApplicationException(503);
        for(String organization:organizations.keySet()){
            var entry=organizations.getJsonObject(organization);
            if(user.equalsIgnoreCase(entry.getString("username"))||entry.containsKey("coordination")&&user.equalsIgnoreCase(entry.getJsonObject("coordination").getString("username")))throw new WebApplicationException(503);
        }
        return basic(Json.createObjectBuilder().add("username",user).add("password",password).build());
    }
    public String nativeOrganization(String organization) {
        authorization(organization);
        return organizations.getJsonObject(organization).getString("edenOrganizationId");
    }
}
