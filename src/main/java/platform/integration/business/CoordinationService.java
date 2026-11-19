package platform.integration.business;

import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import platform.integration.domain.CoordinationInput;
import platform.integration.domain.OperationIdentity;
import platform.integration.persistence.IntegrationStore;
import java.io.StringReader;
import java.util.UUID;
import jakarta.ws.rs.BadRequestException;
import platform.integration.domain.CoordinationPort;
import java.util.Set;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class CoordinationService {
    @EJB DisasterContextService context;
    @Inject CoordinationPort port;
    @EJB IntegrationStore store;
    public JsonObject list(String organization, Set<Integer> grants, Integer disaster, int page, int size, boolean tasks) {
        if (disaster == null || disaster <= 0 || page < 0 || size < 1 || size > 100 || (long)page * size > Integer.MAX_VALUE - size)
            throw new BadRequestException();
        String event = context.existingEvent(organization, grants, disaster);
        return tasks ? port.tasks(organization, disaster, event, page, size) : port.activities(organization, disaster, event, page, size);
    }
    public record Result(int status, JsonObject body) {}
    public Result write(String organization, String actor, Set<Integer> grants, boolean task, String target, UUID request, JsonObject body) {
        var input = CoordinationInput.parse(body, task);
        if (input.organizationId() != null && !organization.equals(input.organizationId())) throw new WebApplicationException(403);
        if (target != null && !target.matches("[1-9][0-9]{0,17}")) throw new BadRequestException();
        String event = context.existingEvent(organization, grants, input.disasterId());
        var canonical = Json.createObjectBuilder(); body.keySet().stream().sorted().forEach(k -> canonical.add(k, body.get(k)));
        String kind = task ? "TASK" : "ACTIVITY";
        String command = OperationIdentity.scoped(kind, target == null ? "" : target, canonical.build().toString());
        var receipt = store.claimResource(OperationIdentity.key("coordination", organization, request.toString()), organization,
            actor, kind, command, input.disasterId(), target == null ? null : kind + "/" + target);
        if (receipt.result != null) {
            if (receipt.status >= 300) throw new WebApplicationException(receipt.status);
            try (var reader = Json.createReader(new StringReader(receipt.result))) {
                return new Result(200, port.get(organization, input.disasterId(), event, task, reader.readObject().getString("id")));
            }
        }
        // Once a native write might have been submitted, do not release the target for a different command.
        boolean[] dispatched = {receipt.dispatched};
        try {
            var result = port.write(organization, event, task, target, input, receipt.externalUuid, () -> {
                boolean first = store.markDispatched(receipt.id, receipt.lease); dispatched[0] = true; return first;
            });
            int status = target == null ? 201 : 200;
            store.finish(receipt.id, receipt.lease, Json.createObjectBuilder().add("id", result.getString("id")).build().toString(),status,null,null,null);
            return new Result(status,result);
        } catch (WebApplicationException failure) {
            int status = failure.getResponse().getStatus();
            if (!dispatched[0] && Set.of(400,403,404,409,422).contains(status))
                store.finish(receipt.id,receipt.lease,"{}",status,null,null,null);
            else store.release(receipt.id,receipt.lease);
            throw failure;
        } catch (RuntimeException failure) { store.release(receipt.id,receipt.lease); throw failure; }
    }
}
