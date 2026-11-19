package platform.integration.domain;

/**
 * Puerto de salida: contrato que el núcleo de aplicación define
 * para acceder al directorio de organizaciones externas.
 * Los adaptadores concretos implementan este puerto; el núcleo
 * nunca depende de estructuras Python ni rutas de Eden.
 */
public interface OrganizationPort {

    /**
     * Busca organizaciones en el proveedor externo.
     *
     * @param query  texto libre opcional para filtrar por nombre
     * @param page   página solicitada (base 0)
     * @param size   máximo de elementos por página
     * @return página canónica con hasMore y total nullable
     * @throws jakarta.ws.rs.WebApplicationException 502 si la respuesta externa es inválida,
     *         503 si el proveedor no está disponible, 504 si se agota el tiempo
     */
    OrganizationPage findOrganizations(String query, int page, int size);
    OrganizationDto findOrganization(String id);
    OrganizationDto detail(String organization,String id);
    OrganizationDto write(String organization,String target,OrganizationInput input,java.util.UUID operation,java.util.function.BooleanSupplier beginWrite);
    OrganizationTypePage findOrganizationTypes(int page, int size);
}
