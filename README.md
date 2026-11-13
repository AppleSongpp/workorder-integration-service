# Work Order Integration Service

A small Spring Boot service that lets integrating systems (SDP, QTSOS, ServiceNow) create,
read and progress work orders. It demonstrates JWT-secured access with source-system level
authorization, idempotent creation backed by persistent storage, and a transactional outbox
for `WorkOrderStatusChanged` events.

Design decisions and trade-offs are in [DESIGN.md](DESIGN.md).

## Requirements

- JDK 21
- Maven 3.9+

No database or identity provider needs to be installed. Data is stored in a file-based H2
database under `./data`; Okta and Entra are simulated with locally signed JWTs.

## Build and test

```bash
mvn verify
```

This compiles the service and runs all tests (unit tests plus integration tests that start the
full application with an in-memory database and real, locally signed tokens).

| Test class | Covers |
|---|---|
| `WorkOrderApiTest` | creation, retrieval, status changes, invalid input, invalid transitions, outbox event |
| `AuthenticationTest` | missing, malformed, expired, forged, wrong-audience and untrusted-issuer tokens |
| `AuthorizationTest` | read/write permission matrix and source-system restrictions for all three callers |
| `IdempotencyTest` | repeated requests, payload conflict, key scope, expiry |
| `ConcurrentCreationTest` | parallel requests with one key create exactly one work order |
| `RestartPersistenceTest` | idempotency keys survive an application restart (file database) |
| `WorkOrderStatusTest`, `PermissionTest` | transition table and permission parsing |

## Generate test tokens

Tokens are minted by a small command-line tool that plays the role of the identity providers.
It lives in `src/test` on purpose, so nothing that can issue tokens is packaged into the
service jar.

```bash
mvn -q test-compile exec:java -Dexec.args="sdp"           # prints a token, valid 60 minutes
mvn -q test-compile exec:java -Dexec.args="qtsos"
mvn -q test-compile exec:java -Dexec.args="servicenow 5"  # valid 5 minutes
```

The first run creates two RSA key pairs in `./dev-keys` (git-ignored). **Run it once before
starting the service**: the service reads the public keys from that directory at startup and
refuses to start without them.

| Caller | Simulated IdP | Permissions claim | Read | Write |
|---|---|---|---|---|
| `sdp` | Okta | `scp` | SDP, QTSOS | SDP |
| `qtsos` | Okta | `scp` | SDP, QTSOS | QTSOS |
| `servicenow` | Entra | `roles` | SDP, QTSOS | none |

## Run

```bash
mvn -q test-compile exec:java -Dexec.args="keys"   # only needed once, creates ./dev-keys
mvn spring-boot:run                                # listens on http://localhost:8080
```

Try it (bash):

```bash
SDP=$(mvn -q test-compile exec:java -Dexec.args="sdp")
SNOW=$(mvn -q test-compile exec:java -Dexec.args="servicenow")

# Create (Idempotency-Key is required)
curl -i -X POST localhost:8080/api/workorders \
  -H "Authorization: Bearer $SDP" -H "Idempotency-Key: demo-1" -H "Content-Type: application/json" \
  -d '{"sourceSystem":"SDP","description":"Cooling unit requires maintenance"}'

# Repeat it: same 201 response, header Idempotency-Replayed: true, no second work order
# Change the description and repeat it: 409 IDEMPOTENCY_KEY_REUSED

# Read (use the id from the response above)
curl -i localhost:8080/api/workorders/WO-1001 -H "Authorization: Bearer $SNOW"

# Change status
curl -i -X PATCH localhost:8080/api/workorders/WO-1001/status \
  -H "Authorization: Bearer $SDP" -H "Content-Type: application/json" \
  -d '{"status":"IN_PROGRESS"}'

# ServiceNow may not write: 403
curl -i -X PATCH localhost:8080/api/workorders/WO-1001/status \
  -H "Authorization: Bearer $SNOW" -H "Content-Type: application/json" \
  -d '{"status":"COMPLETED"}'
```

To start from an empty database, stop the service and delete `./data`.

## API

All endpoints require `Authorization: Bearer <JWT>`.

| Endpoint | Success | Notes |
|---|---|---|
| `POST /api/workorders` | `201` + `Location` | Body: `sourceSystem` (`SDP`, `QTSOS`), `description` (1–1000 chars). Header `Idempotency-Key` required (1–128 chars of `A-Z a-z 0-9 . _ : -`). Response header `Idempotency-Replayed: true/false`. |
| `GET /api/workorders/{id}` | `200` | |
| `PATCH /api/workorders/{id}/status` | `200` | Body: `status` (`OPEN`, `IN_PROGRESS`, `COMPLETED`), optional `version` (the version you last read). |

Work order:

```json
{ "id": "WO-1001", "sourceSystem": "SDP", "description": "Cooling unit requires maintenance", "status": "OPEN", "version": 1 }
```

Errors are `application/problem+json` (RFC 9457) with a stable `code`:

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | A field is missing or invalid; `errors` lists the fields |
| 400 | `MALFORMED_REQUEST` | Body is not valid JSON, or has an unknown field or enum value |
| 400 | `INVALID_IDEMPOTENCY_KEY` / `BAD_REQUEST` | Key has a bad format / header is missing |
| 401 | `UNAUTHENTICATED` | Token missing or rejected (the reason is logged, never returned) |
| 403 | `ACCESS_DENIED` | Token is valid but lacks the permission for this source system |
| 404 | `WORK_ORDER_NOT_FOUND` | |
| 409 | `INVALID_STATUS_TRANSITION` | Includes `currentStatus` and `allowedStatuses` |
| 409 | `VERSION_CONFLICT` | `version` is stale, or a concurrent update won |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Same key, different payload |
| 409 | `REQUEST_IN_PROGRESS` | Same key is still being processed; retry (`Retry-After`) |

## Project layout

Packaged by layer:

```
src/main/java/com/qts/sdp/workorder
  controller/   REST endpoints
  dto/          request/response bodies and objects passed between layers
  service/      use cases, transactions, idempotency, access policy, outbox writer
  repository/   Spring Data repositories
  model/        JPA entities, status lifecycle, source systems
  event/        WorkOrderStatusChanged event
  exception/    business exceptions and the global error-response mapping
  security/     caller identity, permissions, JWT claim mapping, 401/403 rendering
  config/       security filter chain and typed configuration properties
src/main/resources/db/migration   Flyway schema
src/test/java/.../devtools        simulated identity providers and token CLI
```

## Configuration

See `src/main/resources/application.yaml`: datasource, trusted issuers
(`workorders.security.issuers.*`), required audience, idempotency retention and purge interval.
