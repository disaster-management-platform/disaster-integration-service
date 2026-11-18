package platform.integration.business;

import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.*;
import platform.integration.persistence.IntegrationStore;
import java.io.StringReader;
import java.util.Set;
import java.util.UUID;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class DisasterContextService {
    @Inject DisasterPort disasters;
    @Inject PeoplePort people;
    @EJB IntegrationStore store;

    public JsonObject authorize(String organization, Set<Integer> grants, int disaster) {
        if (disaster <= 0 || !grants.contains(disaster)) throw new WebApplicationException(403);
        return disasters.requireOrganization(disaster, organization);
    }

    public String existingEvent(String organization, Set<Integer> grants, int disaster) {
        authorize(organization, grants, disaster);
        var receipt = store.eventReceipt(organization, disaster);
        if (receipt == null || receipt.result == null) throw new WebApplicationException(409);
        return eventId(receipt.result);
    }

    public String correlate(String organization, String actor, Set<Integer> grants, int disaster) {
        JsonObject owner = authorize(organization, grants, disaster);
        var previous = store.eventReceipt(organization, disaster);
        if (previous != null && previous.result != null) return eventId(previous.result);
        String source = "disaster-management-platform";
        UUID key = previous == null ? OperationIdentity.key("event", organization, Integer.toString(disaster)) : previous.id;
        String command = previous == null ? Json.createObjectBuilder().add("source", source).add("disasterId", disaster).build().toString() : previous.command;
        var receipt = store.claim(key, organization, actor, "EVENT", command, disaster, null);
        if (receipt.result != null) return eventId(receipt.result);
        try {
            String name = owner.getString("nombre", "");
            if (name.isBlank()) throw new WebApplicationException(502);
            String event = people.event(organization, receipt.externalUuid, disaster, name, source);
            store.finish(receipt.id, receipt.lease, Json.createObjectBuilder().add("eventId", event).build().toString(), 200, null, null, null);
            return event;
        } catch (RuntimeException e) { store.release(receipt.id, receipt.lease); throw e; }
    }

    private static String eventId(String value) {
        try (var reader = Json.createReader(new StringReader(value))) {
            String id = reader.readObject().getString("eventId");
            if (!id.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException e) { throw new WebApplicationException(502); }
    }
}
