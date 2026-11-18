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
@jakarta.ejb.TransactionAttribute(jakarta.ejb.TransactionAttributeType.NOT_SUPPORTED)
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
        int p=page==null?0:page;
        int s=size==null?DEFAULT_PAGE_SIZE:size;
        if(p<0||s<1||s>MAX_PAGE_SIZE||(long)p*s>Integer.MAX_VALUE-s||query!=null&&query.length()>200)throw new jakarta.ws.rs.BadRequestException();
        String q=query==null||query.isBlank()?null:query.strip();
        return port.findOrganizations(q, p, s);
    }
}
