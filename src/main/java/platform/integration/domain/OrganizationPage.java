package platform.integration.domain;

import java.util.List;
import jakarta.json.bind.annotation.JsonbNillable;

/**
 * Resultado paginado de organizaciones.
 * total es null cuando el proveedor no devuelve un conteo exacto.
 */
@JsonbNillable
public record OrganizationPage(
    List<OrganizationDto> items,
    int page,
    int pageSize,
    Integer total,
    boolean hasMore
) { }
