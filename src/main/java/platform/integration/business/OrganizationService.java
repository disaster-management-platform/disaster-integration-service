package platform.integration.business;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import platform.integration.domain.*;

/**
 * Caso de uso: consulta del directorio de organizaciones.
 * Stateless EJB — sin estado mutable en campos; pooling administrado por WildFly.
 * Aplica reglas de integración sin depender de protocolos ni estructuras de Eden.
 */
@Stateless
public class OrganizationService {

    // Límites de paginación de integración
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 25;

    @Inject
    OrganizationPort port;

    /**
     * Retorna la página de organizaciones autorizada para el consumidor.
     * Valida parámetros antes de delegar al adaptador.
     */
    public OrganizationPage listOrganizations(String query, Integer page, Integer size) {
        int p = (page != null && page >= 0) ? page : 0;
        int s = (size != null && size > 0 && size <= MAX_PAGE_SIZE) ? size : DEFAULT_PAGE_SIZE;
        String q = (query != null && !query.isBlank()) ? query.strip() : null;
        return port.findOrganizations(q, p, s);
    }
}
