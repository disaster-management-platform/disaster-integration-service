package platform.integration.business;

import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.BadRequestException;
import platform.integration.domain.CoordinationPort;
import java.util.Set;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class CoordinationService {
    @EJB DisasterContextService context;
    @Inject CoordinationPort port;
    public JsonObject list(String organization, Set<Integer> grants, Integer disaster, int page, int size, boolean tasks) {
        if (disaster == null || disaster <= 0 || page < 0 || size < 1 || size > 100 || (long)page * size > Integer.MAX_VALUE - size)
            throw new BadRequestException();
        String event = context.existingEvent(organization, grants, disaster);
        return tasks ? port.tasks(organization, disaster, event, page, size) : port.activities(organization, disaster, event, page, size);
    }
}
