package platform.integration.domain;

import jakarta.json.bind.annotation.JsonbNillable;

/**
 * DTO canónico de organización — contrato propio de la plataforma.
 * Independiente de los modelos Python y tablas internas de Eden.
 * Campos opcionales son null cuando Eden no los provee; nunca se fabrican valores ficticios.
 */
@JsonbNillable
public record OrganizationDto(
    String id,
    String name,
    String typeId,
    java.util.List<String> typeIds,
    String acronym,
    String country,
    String locationId,
    String phone,
    String email,
    String description,
    java.util.List<String> personnelIds,
    java.util.List<String> volunteerIds,
    java.util.List<String> facilityIds,
    java.util.List<Integer> disasterIds
) { }
