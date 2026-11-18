package platform.integration.domain;

import java.util.List;

/**
 * Resultado paginado de organizaciones.
 * total refleja el número real de registros en Eden; no se trunca ni se oculta.
 */
public record OrganizationPage(
    List<OrganizationDto> items,
    int page,
    int pageSize,
    int total
) { }
