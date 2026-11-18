# Integration Service

Java 21, Jakarta EE 11, WildFly Preview 42, JAX-RS, Stateless EJB y CDI. Un WAR mantiene REST Resource → OrganizationService → OrganizationPort → OrganizationAdapter → EdenHttpClient → API nativa Eden. El EJB usa NOT_SUPPORTED: ninguna transacción JTA permanece abierta durante HTTP.

## Contrato

GET /api/organizaciones requiere JWT con org.read, org.admin o platform.admin, sub y organization_id. Es un directorio global autorizado de organizaciones; este permiso no habilita personas, familias ni expedientes. La autorización precede a las consultas externas.

Parámetros públicos: q (hasta 200 caracteres), pagina (base cero, no negativa), tamanio (1–100; predeterminado 25). Los valores explícitamente inválidos o desplazamientos que desbordan integer producen 400. Se retiraron alias no documentados sin consumidores.

Respuesta: items, page, pageSize, total: null, hasMore. Se solicita un registro adicional a Eden para conocer la página siguiente; no se descarga el directorio completo ni se devuelve X-Total-Count estimado. La paginación no ofrece un snapshot transaccional frente a cambios simultáneos en Eden.

Cada organización conserva id, name, typeId, acronym, country, locationId, phone, email y description. typeIds añade todos los identificadores numéricos nativos de org_organisation_type, como strings estables. typeId solo se informa cuando existe un único tipo. ORG-01 debe admitir typeId null y utilizar typeIds para tipos múltiples, sin fabricar un primario. locationId procede del componente nativo organisation_location.location_id (gis_location); es null cuando no existe una única ubicación. pe_id referencia pr_pentity y nunca se publica como ubicación. Las asociaciones geográficas se consultan con límite 2; no se elige arbitrariamente una entre varias.

Referencias revisadas: Eden 6.3, org.py, relación org_organisation_organisation_type y componente org_organisation_location; el exportador JSON devuelve IDs sin represent=true. Una respuesta malformada produce 502, sin omitir filas silenciosamente.

GET /api/integracion/diagnostico/live es independiente de Eden. GET /api/integracion/diagnostico/ready realiza una consulta ORG mínima autenticada y valida su estructura. Credenciales técnicas rechazadas, redirecciones y datos inválidos producen readiness 503. No se confunden con el JWT inválido del consumidor (401/403).

## Configuración y ejecución

```sh
mvn package
export JWT_ISSUER=https://identity.example.org
export JWT_AUDIENCE=integration-service
export JWT_PUBLIC_KEY_LOCATION=https://identity.example.org/jwks
export EDEN_BASE_URL=http://127.0.0.1:8001/eden_default
export EDEN_TECH_USER=integration-reader@example.org
# Cargue EDEN_TECH_PASSWORD desde un secreto externo.
"$WILDFLY_HOME/bin/standalone.sh"
"$WILDFLY_HOME/bin/jboss-cli.sh" --connect --command="deploy target/integration-service.war"
```

MicroProfile Config enlaza JWT_ISSUER, JWT_AUDIENCE y JWT_PUBLIC_KEY_LOCATION con las propiedades MP-JWT. WildFly verifica firma, issuer, audiencia y expiración; role se interpreta como grupos. No se implementa un proveedor de identidad. El contexto es / y no debe compartirlo con otra aplicación.

HTTPS es obligatorio fuera de loopback, salvo autorización explícita de desarrollo mediante EDEN_ALLOW_DEVELOPMENT_HTTP=true. TLS conserva la validación del sistema; no se deshabilitan certificados. Nunca utilice credenciales administrativas. El administrador de Eden debe conceder únicamente lectura ORG y lectura del componente geográfico, sin escritura ni administración; los permisos sobre otros módulos deben revisarse en el despliegue correspondiente.

El HttpClient reutilizable tiene conexión de 5 s y recepción total de 10 s, máximo 4 MiB y 8 operaciones concurrentes por instancia. El exceso cancela la recepción; la saturación devuelve 503. Los fallos transitorios se recuperan en la siguiente solicitud sin reiniciar WildFly. No se registran cuerpos, credenciales ni mensajes externos. Las consultas geográficas añaden solicitudes acotadas por organización; se prefiere semántica correcta a referencias inventadas.

## Validación dirigida

```sh
export INTEGRATION_TEST_URL=http://127.0.0.1:8420/api
export INTEGRATION_TEST_PRIVATE_KEY=/ruta/privada/test.pem
python3 scripts/test_integration.py --targeted
```

El script usa issuer y audiencia externos. La variante dirigida comprueba traducción real, dos páginas y fin, parámetros inválidos y configuración JWT. El OpenAPI publicado en src/main/resources/META-INF/openapi.json describe únicamente las tres operaciones existentes. No hay persistencia, acceso a tablas Eden, Supabase ni modificaciones a otros dominios.

Las pruebas de fallos se ejecutan con `--faults`, INTEGRATION_TEST_URL apuntando a una instancia aislada y INTEGRATION_TEST_FAULT_CONTROL apuntando al control del proxy de fallos de pruebas. Cubren credenciales técnicas inválidas, caída/recuperación, redirecciones, cuerpo inválido, exceso de bytes y recepción lenta. El fixture de tipos múltiples y ubicación no se presenta como datos reales de Eden.

## Personas afectadas (07B.2)

La plantilla platform de sahana-eden habilita PR/DVR/CR/EVENT con política nativa de realms organizacionales. Registro, familias, expedientes y refugios se operan en Eden mediante sus interfaces nativas; no existe todavía un consumidor implementado que justifique duplicar esas operaciones en Jakarta. El permiso org.read del directorio global no concede acceso a datos personales. Las identidades técnicas y las sesiones web de Eden se aprovisionan por organización y nunca utilizan al administrador en runtime. No se realiza conversión implícita de disasterId a un identificador EVENT de Eden.
