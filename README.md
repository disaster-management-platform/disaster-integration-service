# Integration Service

Java 21, Jakarta EE 11, WildFly 42, JAX-RS, Stateless EJB, CDI and MicroProfile JWT. The service consumes Eden's native APIs through ports and adapters; Eden retains ownership of its business rules and records. Desastres owns disasters and external report receipts. No adapter accesses another service's database.

## REST contracts

The contract is in `src/main/resources/META-INF/openapi.json`.

| Method | Path | Permission |
|---|---|---|
| GET | `/api/organizaciones`, `/api/organizaciones/{id}`, `/api/organizaciones/tipos` | `org.read`, `org.admin` or `platform.admin` |
| GET | `/api/personas-afectadas`, `/api/personas-afectadas/{id}`, `/api/personas-afectadas/resumen` | `people.read` or `people.write` |
| POST, PUT | `/api/personas-afectadas`, `/api/personas-afectadas/{id}` respectively | `people.write` |
| GET | `/api/coordinacion/tareas`, `/api/coordinacion/actividades` | `coordination.read` or `coordination.write` |
| POST | `/api/integracion/reportes/{category}` | `integration.{category}` |
| GET | `/api/integracion/diagnostico/live`, `/api/integracion/diagnostico/ready` | Internal diagnostics; restrict exposure at the gateway/network |

The global organization directory does not authorize access to persons. `typeIds` preserves multiple native organization type IDs; `typeId` is null unless there is exactly one type. `locationId` is the native geographic reference, not `pe_id`. A single S3JSON export requests the type/location components rather than issuing one request per organization; check native component selectors in the deployed template. Pagination returns `hasMore` with `total: null` where no exact count is available.

### People and disaster context

An authorized user must have an organization delegation; disaster-bound operations also require a `disaster_id` grant and current organization membership confirmed by the owner. A locally persisted correlation binds the native person ID **and UUID** to its organization and optional disaster. Records created only in Eden are not automatically classified as uncorrelated: they require an explicit verified association before being exposed via the platform API. Without a disaster filter, the API lists only uncorrelated platform registrations. Search is applied to each bounded page of links; a page may be empty even when `hasMore` is true. Contacts and relationships are omitted from list responses.

Writes require a UUID `Idempotency-Key`, scoped to organization with actor verification. Receipts save the command digest and native identifier, not copies of PII. Replays return the current authorized view. An outstanding update reserves its native person; a different update cannot proceed until the first is resolved. After a possible submission, a retry checks the native operation tag before accepting success; an absent marker leaves the result uncertain, rather than resubmitting an update. Investigate uncertain operations in Eden before making administrative repairs. Never remove the pending receipt merely to bypass a conflict.

Optional fields omitted or null on updates leave their original native values unchanged; explicit clearing requires a verified native operation. Changing an existing disaster association is not supported by PUT. Native creation and event imports use stable UUIDs. Event correlation is stable per platform organization and disaster, **not** a verified globally unique event shared between organizations. Coordination uses only persisted correlations, not names or equal numeric IDs.

People counts are returned only for a verified roster of at most 100 native IDs and UUIDs; an empty platform roster counts as zero. Family, open case, shelter, zone and needs aggregates are null until their own event/realm filters are proven. Detail also returns null for unverified family, case and shelter references. These are not claims about the absence of data in Eden; the native screens remain available under Eden authorization. `EDEN_PUBLIC_BASE_URL` optionally creates a native link without secrets; it does not bypass Eden login.

### Coordination and logistics

The read adapters query projects linked to an authorized Eden EVENT and include only projects led by the explicitly mapped native organization. Up to 100 projects are supported per operation. The adapter uses three bounded native queries: event-linked project IDs, grouped project ownership, then grouped tasks or activities. It checks each returned `project_id` against those projects. Missing correlation returns 409. Native activity dates have day precision (`startsOn`, `endsOn`); no timestamp is invented. Assignee/contact references remain null until their scope and native subtype are verified. These adapters require native EVENT/PROJECT access and role configuration in `sahana-eden`; do not route them to production before validating the native selectors and ACLs.

ORG mutations, coordination mutations, and personnel/facility relationships still require native authorization and contracts. Families, needs, cases, shelter registrations and SUPPLY/INV/REQ remain native Eden workflows. No independent Java stock calculations, resource assignments or financial budgets exist here. A commitment is not physical delivery.

### External report ingress

The allowed categories are `monitoring`, `gis`, `hospital`, `administrative` and `financial`. The authenticated JWT role must match the category. A verified source identity is derived from category, issuer, organization and subject; a caller cannot supply its own source ID or registering organization. This assumes one stable authenticated principal per source. Send a stable `sourceReportId` and the original `content` object (up to 64 KiB). Only monitoring may include a canonical `disaster` object to request a preliminary disaster. Desastres validates and persists it through `/api/reportes-desastre`; its existing uniqueness/receipt mechanism provides replay and conflict behavior. On a timeout, retry the same source report and unchanged content rather than generating a new ID. The service returns the authoritative receipt ID, external report ID and nullable disaster ID.

No third-party payload format or real provider has been selected for these categories. The ingress is a normalized report interface, not a claim that hospital, GIS or financial provider protocols have been deployed. It does not authorize or transfer money.

## Configuration

Build with `mvn clean package` and deploy `target/integration-service.war` to WildFly with Java 21. Configure the context `/` on a separate application server/listener as needed.

| Environment variable | Purpose |
|---|---|
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_PUBLIC_KEY_LOCATION` | Verified JWT issuer, audience and public key/JWKS location; `role` maps to groups |
| `EDEN_BASE_URL`, `EDEN_TECH_USER`, `EDEN_TECH_PASSWORD` | Native Eden URL and technical read-only ORG identity |
| `EDEN_PUBLIC_BASE_URL` | Optional native browser URL |
| `EDEN_ALLOW_DEVELOPMENT_HTTP` | Optional explicit development-only exception for HTTP outside loopback |
| `EDEN_DELEGATIONS_FILE` | Private JSON mapping of platform organization IDs to native organization IDs and dedicated credentials |
| `DISASTERS_BASE_URL` | Owner base URL (no `/api` suffix), HTTPS outside loopback |
| `DISASTERS_READ_OAUTH_TOKEN_ENDPOINT`, `DISASTERS_READ_OAUTH_CLIENT_ID`, `DISASTERS_READ_OAUTH_CLIENT_SECRET` | HTTPS Client Credentials endpoint and least-privilege owner read identity |
| `DISASTERS_REPORT_OAUTH_TOKEN_ENDPOINT`, `DISASTERS_REPORT_OAUTH_CLIENT_ID`, `DISASTERS_REPORT_OAUTH_CLIENT_SECRET` | Separate owner ingestion identity with `disasters.integration` |
| `INTEGRATION_DB_HOST`, `INTEGRATION_DB_PORT`, `INTEGRATION_DB_NAME`, `INTEGRATION_DB_CA`, `INTEGRATION_DB_USER`, `INTEGRATION_DB_PASSWORD` | PostgreSQL JNDI configuration in `server/datasource.cli` |

Keep credentials outside Git and restrict local secret files (0600). Do not use administrative Eden credentials in runtime. Each organization entry in `EDEN_DELEGATIONS_FILE` contains `edenOrganizationId`, `username`, `password` for people and, optionally, a nested `coordination` object with its own `username` and `password`. Distinct organizations must use distinct native organization IDs; usernames must be unique across the file. Provision and verify the actual realms and permissions in Eden. Credentials are not forwarded from user JWTs to Eden. Configure separate scoped owner tokens, rotate externally, and never enable development authentication in production.

PostgreSQL holds only integration receipts and correlations, not replicated Eden data. Install the approved PostgreSQL JDBC driver in WildFly, create the `integration_api` role and a migration owner, and configure `java:jboss/datasources/Integration` using `server/datasource.cli`. Apply `migrations/V001...` through `V003...` in order with a migration role to an isolated database first, then `server/permissions.sql`. Existing deployments must apply only versions not present in `integration.schema_history`. V002 refuses unresolved legacy updates or duplicate event correlations; V003 refuses person links whose creation UUID cannot be proven. Back up and reconcile those records against Eden before a migration; the scripts intentionally do not guess or overwrite associations. No schema changes occur at startup. Runtime schema validation is enabled.

Eden and Desastres use independent outbound bulkheads (8 and 4 operations per instance respectively), a 4 MiB streaming response limit, 5 s connection timeout, at most 10 s per HTTP call and a shared 20 s request budget. Redirects are not followed. Incoming POST/PUT/PATCH entities are capped at 256 KiB by `RequestLimit`; server/gateway request timeouts remain necessary. These limits are not performance-SLA measurements. `live` checks the local service; `ready` verifies a small authenticated ORG query and does not certify the optional downstream integrations.

## Validation

```sh
./scripts/test_local.sh              # Java 21 only; local HTTP server and identity checks
mvn clean package                   # Requires Jakarta EE / MicroProfile dependencies
```

After deploying to an isolated environment with synthetic records, set `INTEGRATION_TEST_URL`, `INTEGRATION_TEST_PRIVATE_KEY`, `JWT_ISSUER`, `JWT_AUDIENCE` as appropriate and run:

- `python3 scripts/test_integration.py --targeted`: native ORG and JWT checks. The fault variant also needs a fault proxy exposing the S3JSON component format used by the organization adapter.
- `python3 scripts/people_test.py`: PEO flows with delegated organizations (`EDEN_TEST_IDENTITIES`), a real owner disaster fixture (`INTEGRATION_TEST_DISASTER_ID`), migrations and native permissions. `--recovery-only` exercises a committed native update with a lost response; its test proxy uses `INTEGRATION_TEST_DROP_CONTROL` to drop one successful update response and records `dropped` after forwarding it.
- `python3 scripts/external_reports_test.py`: real owner receipts, replay, conflict and source/category authorization (`INTEGRATION_TEST_ORGANIZATION`).
- `python3 scripts/coordination_test.py`: scoped read checks with an existing event/project fixture (`INTEGRATION_TEST_ORGANIZATION`, `INTEGRATION_TEST_DISASTER_ID`, `INTEGRATION_TEST_TASK_ID`, `INTEGRATION_TEST_ACTIVITY_ID`).

Install `requests` and `PyJWT` for the Python checks. `scripts/test_integration.py --merge` verifies native organization detail, multiple types and geographic references using `INTEGRATION_TEST_ORGANIZATION`, `INTEGRATION_TEST_TYPE_IDS` (JSON array), `INTEGRATION_TEST_LOCATION_ID` and a metadata-only proxy log (`INTEGRATION_TEST_PROXY_LOG`).

Validated locally with Java 21, Maven WAR packaging, WildFly 42, PostgreSQL 18 and real Eden: V002/V003 over the existing V001 schema, PEO contracts and concurrent idempotency, organization/disaster/record isolation, lost-response update recovery, grouped ORG translation, scoped coordination reads, and normalized report receipt/replay/conflict against the actual Disaster Management API. Only synthetic data was used. The isolated owner ran its existing Development authentication; production service credentials and Gateway routing were not validated by these checks. No remote migrations were applied. Native families, cases, shelters, logistics fulfillment, messaging and the absent event/cross-domain workflows are not certified by this validation.

## Keycloak

Set `JWT_ISSUER`, `JWT_AUDIENCE=integration-service` and `JWT_PUBLIC_KEY_LOCATION` to the issuer and JWKS URI. MicroProfile JWT verifies locally with cached signing keys and maps `role` to groups. Configure external JVM CA trust for private development certificates. Existing organization/disaster/resource checks remain authoritative. Owner requests use separate read and report Client Credentials configurations above; static owner bearer tokens are replaced. Acquisition reuses the bounded transport, requires HTTPS, has a maximum three-second timeout, caches until before expiry and fails as a dependency error without anonymous fallback. No JTA transaction spans token acquisition or HTTP calls.

`tests/OAuthChecks.java` checks real acquisition/cache/renewal with `SECURITY_OAUTH_TOKEN_ENDPOINT`, `SECURITY_OAUTH_CLIENT_ID`, `SECURITY_OAUTH_CLIENT_SECRET` and an isolated five-second client. Compile with Java 21 against `target/classes`, Maven dependencies and a JSON-P implementation; run `OAuthChecks` with external CA trust. No tokens are printed.
