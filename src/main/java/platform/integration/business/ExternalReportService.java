package platform.integration.business;

import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.BadRequestException;
import platform.integration.domain.DisasterPort;
import platform.integration.domain.ExternalReportSource;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ExternalReportService {
    @Inject DisasterPort disasters;
    private static final Set<String> FIELDS = Set.of("sourceReportId", "content", "disaster");
    private static final Set<String> DISASTER_FIELDS = Set.of("nombre", "ubicacion", "severidad", "descripcion", "latitud", "longitud", "radio", "disasterTypeId", "startedAt", "attentionLevel");

    public DisasterPort.ReportReceipt receive(String category, String issuer, String organization, String actor, JsonObject input) {
        if (input == null || !FIELDS.containsAll(input.keySet())) throw new BadRequestException();
        String source = ExternalReportSource.identify(category, issuer, organization, actor);
        if (!(input.get("sourceReportId") instanceof JsonString externalId) || externalId.getString().isBlank() ||
            externalId.getString().length() > 200 || !externalId.getString().equals(externalId.getString().strip()) ||
            externalId.getString().codePoints().anyMatch(Character::isISOControl)) throw new BadRequestException();
        if (!(input.get("content") instanceof JsonObject content) || content.toString().getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new BadRequestException();
        var command = Json.createObjectBuilder().add("sourceKind", 1).add("sourceId", source)
            .add("sourceReportId", externalId.getString()).add("registeringOrganizationId", organization).add("content", content);
        var preliminary = input.get("disaster");
        if (preliminary != null && preliminary != JsonValue.NULL) {
            if (!category.equals("monitoring") || !(preliminary instanceof JsonObject disaster) || !DISASTER_FIELDS.containsAll(disaster.keySet()))
                throw new BadRequestException();
            command.add("createPreliminary", true).add("disaster", disaster);
        } else command.add("createPreliminary", false);
        return disasters.receiveReport(command.build());
    }
}
