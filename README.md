# Integration Service

Servicio Jakarta EE 11, Java 21, WildFly 42.0.0.Final — mediador entre la plataforma de gestión de desastres y Sahana Eden.
Implementa Anti-Corruption Layer (Ports and Adapters) para encapsular los modelos, protocolos y mecanismos de autenticación de Eden.

## Endpoints

| Método | Ruta | Autenticación | Rol requerido |
|--------|------|---------------|---------------|
| `GET` | `/api/organizaciones` | JWT Bearer | `org.read`, `org.admin` o `platform.admin` |
| `GET` | `/api/integracion/diagnostico/live` | Ninguna | — |
| `GET` | `/api/integracion/diagnostico/ready` | Ninguna | — |

Parámetros de `/api/organizaciones`:

| Parámetro | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `q` | string | — | Filtro por nombre |
| `pagina` | integer | 0 | Página base 0 |
| `tamanio` | integer | 25 (máx. 100) | Tamaño de página |

Contrato de respuesta (`OrganizationDto`):

```json
{
  "items": [
    {
      "id": "1",
      "name": "Cruz Roja",
      "typeId": null,
      "acronym": null,
      "country": null,
      "locationId": "3",
      "phone": null,
      "email": null,
      "description": null
    }
  ],
  "page": 0,
  "pageSize": 25,
  "total": 2
}
```

Los campos opcionales son `null` cuando Eden no los provee; nunca se fabrican valores ficticios.
`total` puede ser una estimación conservadora cuando Eden no lo devuelve en la respuesta de lista.

## Compilación

```sh
mvn package
```

Produce `target/integration-service.war` (~22 KB).

## Ejecución en WildFly 42

WildFly Preview es necesario para Jakarta EE 11 y MicroProfile JWT completo.

1. Iniciar WildFly con variables de entorno externas:

```sh
export JWT_ISSUER=https://keycloak.example.com/realms/platform
export JWT_PUBLIC_KEY_LOCATION=https://keycloak.example.com/realms/platform/protocol/openid-connect/certs
export JWT_AUDIENCE=integration-service

export EDEN_BASE_URL=http://127.0.0.1:8001/eden_default
export EDEN_TECH_USER=integration@example.com
export EDEN_TECH_PASSWORD=<contraseña_identidad_tecnica>

$WILDFLY_HOME/bin/standalone.sh
```

2. Desplegar el WAR:

```sh
$WILDFLY_HOME/bin/jboss-cli.sh --connect --command="deploy target/integration-service.war"
```

El contexto HTTP es `/`; no instale este WAR en el mismo contexto raíz de otra aplicación.

## Variables de entorno

| Variable | Descripción |
|----------|-------------|
| `JWT_ISSUER` | Issuer del JWT de la plataforma (Keycloak). |
| `JWT_PUBLIC_KEY_LOCATION` | JWKS HTTPS o clave pública PEM del emisor. |
| `JWT_AUDIENCE` | Audiencia esperada en el JWT. |
| `EDEN_BASE_URL` | URL base de la aplicación Eden `default` (sin barra final). |
| `EDEN_TECH_USER` | Email de la identidad técnica restringida en Eden. |
| `EDEN_TECH_PASSWORD` | Contraseña de la identidad técnica. Nunca administrativa. |

Configure MicroProfile JWT externamente via MicroProfile Config, variables de entorno o propiedades JVM.
No se proporcionan claves predeterminadas ni credenciales en el repositorio.

## Identidad técnica de Eden

Crear en Eden una cuenta de solo lectura específica para el servicio:

```
Email:    integration@example.com   (no usar admin@example.com)
Password: <contraseña_fuerte>
Rol:      Organisation Manager (lectura) o equivalente mínimo
```

Usar `admin@example.com` exclusivamente para crear esta cuenta.
La identidad técnica no debe tener permisos administrativos en Eden.

## Seguridad JWT

El JWT debe incluir:
- `sub`: identificador del actor.
- `organization_id`: organización del consumidor.
- `role`: `org.read`, `org.admin` o `platform.admin` para acceder al directorio.
- `iat`, `exp`, `iss`, `aud` conforme a MP-JWT.

`smallrye.jwt.path.groups=role` utiliza el claim `role` como fuente de grupos/roles.

## Integración con YARP (pendiente)

Ruta a enrutar desde el Gateway:

```
/api/organizaciones         → http://<wildfly-host>:8080/api/organizaciones
/api/integracion/diagnostico → http://<wildfly-host>:8080/api/integracion/diagnostico
```

El Gateway debe reenviar el encabezado `Authorization: Bearer <JWT>` sin modificarlo.
La autenticación técnica contra Eden es responsabilidad exclusiva del Integration Service.

## Arquitectura

Implementa Ports and Adapters (Hexagonal Architecture) dentro de un servicio SBA:

```
JAX-RS Resource          ← adaptador de entrada
  ↓ Identity (CDI)       ← verificación JWT MP-JWT
  ↓ OrganizationService  ← EJB Stateless (caso de uso)
  ↓ OrganizationPort     ← puerto de salida (contrato)
  ↓ OrganizationAdapter  ← adaptador ACL Eden
  ↓ EdenHttpClient       ← cliente HTTP reutilizable
  ↓ Eden REST API        ← proveedor externo
```

Patrones aplicados: Anti-Corruption Layer, Adapter, DTO, Mapper/Translator, Stateless Components,
Dependency Inversion, Information Hiding, Fault Isolation, Timeout, Connection Reuse.

## Decisiones y trade-offs

| Decisión | Razón | Trade-off |
|----------|-------|-----------|
| `java.net.http.HttpClient` singleton CDI | Reutilización de conexiones; sin dependencias adicionales | No admite reconexión automática; se recupera en la siguiente solicitud |
| Sin persistencia en 07B.1 | La consulta de organizaciones es stateless; no requiere correlación de IDs | Las correspondencias durables (futuras) requerirán esquema independiente |
| `total` estimado | Eden no incluye total en la respuesta de lista; calcular requeriría una segunda solicitud | El consumidor conoce que puede haber más páginas pero no el total exacto |
| Basic Auth técnica | Eden 6.3/web2py admite Basic Auth sobre HTTP en entorno local controlado | En producción debe usarse HTTPS entre Integration Service y Eden |
| Roles propios de la plataforma | Desacopla el modelo de permisos de Eden del de la plataforma | Requiere coordinación al incorporar nuevos módulos Eden |

## Contratos OpenAPI

Disponible en `src/main/resources/META-INF/openapi.json`.

## Limitaciones menores (07B.1)

- `total` en la respuesta es una estimación conservadora; Eden 6.3 no expone totales en la lista JSON.
- Los campos `country`, `phone`, `email` son `null` en la instancia `default` actual (sin datos de contacto cargados).
- `typeId` es `null` si la organización no tiene tipo asignado en Eden.
- HTTPS entre Integration Service y Eden queda pendiente para entornos de producción.
