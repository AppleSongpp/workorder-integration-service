# Design

## 1. Status transition rules

```
OPEN ──▶ IN_PROGRESS ──▶ COMPLETED (terminal)
  ▲           │
  └───────────┘
```

| From | Allowed targets |
|---|---|
| `OPEN` | `IN_PROGRESS` |
| `IN_PROGRESS` | `COMPLETED`, `OPEN` (work released back to the queue) |
| `COMPLETED` | none |

- Rules live in `WorkOrderStatus`; the `WorkOrder` entity enforces them, so no caller can bypass them.
- An invalid transition returns `409 INVALID_STATUS_TRANSITION` with the current and allowed
  statuses. 409 rather than 422 because the request is well-formed and only conflicts with the
  resource's current state.
- Setting the status a work order already has is a successful no-op (no version bump, no event).
  This makes a retried `PATCH` safe without an idempotency key.
- Every change increments `version` (JPA `@Version`). Callers may send the version they last
  read; a stale value, or losing a concurrent update, returns `409 VERSION_CONFLICT`.

## 2. Idempotency

**Scope of a key.** A key is scoped to the authenticated caller (token issuer + subject) and to
`POST /api/workorders`, the only endpoint that accepts one. Two callers can use the same key
without affecting each other, and a caller cannot probe another caller's keys. The header is
required: for a system-to-system API every create should be retry-safe.

**What is stored.** One row in `idempotency_record` per successful creation: caller, key,
SHA-256 of the request, the work order id, and the original response (status and body).
`UNIQUE (client_id, idempotency_key)` is the guard.

**Behaviour.**

| Situation | Result |
|---|---|
| First request | `201`, work order created, `Idempotency-Replayed: false` |
| Same key, same payload | Original `201` and body, `Idempotency-Replayed: true`, even if the work order has changed since |
| Same key, different payload | `409 IDEMPOTENCY_KEY_REUSED` |
| Same key while the first is still running and the wait times out | `409 REQUEST_IN_PROGRESS` + `Retry-After` |

The payload hash is computed over the parsed fields, not raw bytes, so a retry that differs only
in JSON formatting is the same request. Requests that fail (400, 401, 403) store nothing, so the
key can be reused after fixing the request.

**Concurrency.** The work order and its idempotency record are inserted in **one database
transaction** (`WorkOrderCreator`). When two requests race, the second insert of the same key
blocks on the unique index until the first transaction ends, then fails; its transaction,
including its own work order row, rolls back. The service then reads the winner's record in a
new transaction and replays it (`WorkOrderService.create`). Because both rows commit atomically
there is no "in progress" marker that could be left behind by a crash: a key either points to a
finished work order or does not exist. The trade-off is that a loser waits (bounded by the
database lock timeout, 10 s) instead of failing fast, and a sequence number is skipped.
I chose this over an application lock or `synchronized` because it stays correct with several
service instances, and over a separate "claim key, then create" step because that needs
recovery logic for claims whose owner died.

**Persistence and restarts.** Records are in the same database as work orders (file-based H2
locally), so they survive restarts; `RestartPersistenceTest` restarts the application on the
same database file and replays a request.

**Retention.** 24 hours (`workorders.idempotency.retention`), long enough to cover client retry
policies and queue redeliveries. A scheduled job deletes expired rows every 15 minutes. A key is
honoured until its row is purged, so the guarantee is "at least 24 hours"; after that the same
key creates a new work order.

## 3. Authentication and authorization

**Now.** The service is an OAuth2 resource server trusting two simulated issuers, each with its
own RSA key. The token's `iss` selects the issuer configuration (unknown issuers are rejected);
the token is then validated with that issuer's key only: RS256 signature, `exp` (required),
`nbf`, exact `iss`, `aud = api://workorders`, non-empty `sub`.

Permissions come only from the validated token, as `workorders:<read|write>:<SOURCE_SYSTEM>`
values in the issuer's permissions claim (`scp` for Okta, `roles` for Entra). The business layer
(`WorkOrderAccessPolicy`) checks them against the source system of the data: the requested owner
for a create, the **stored** owner for read and status change. Nothing is read from query
parameters or custom headers. Failures return a generic 401 or 403; the reason is only logged,
and tokens are never logged.

**Adapting to real Okta and Entra.** Mostly configuration:

| | Okta | Entra ID |
|---|---|---|
| Flow | Client credentials per service app | Client credentials per app registration |
| Keys | `jwk-set-uri` = the authorization server's `/v1/keys` | `jwk-set-uri` = tenant `discovery/v2.0/keys` |
| Issuer | Custom authorization server URL | `https://login.microsoftonline.com/{tenant}/v2.0` |
| Audience | Audience of the authorization server | Application ID URI or client id of the API app |
| Permissions | Custom scopes, granted per client by access policies, in `scp` | App roles assigned to each client's service principal, in `roles` |
| Caller id | `sub` / `cid` | `azp` / `oid` (not `sub` alone) |

Each issuer entry already accepts `jwk-set-uri` instead of a key file, which brings key rotation
via JWKS caching. Remaining work: confirm the exact claim shapes against real tokens, choose the
caller-id claim per issuer, accept Entra v1 tokens if the API registration emits them, and add
contract tests using captured (expired) sample tokens. That code path is untested here.

## 4. `WorkOrderStatusChanged` event

```json
{
  "eventId": "6f1c1c0e-6d1b-4a55-9d3b-0b6c1d2f9a11",
  "eventType": "WorkOrderStatusChanged",
  "schemaVersion": 1,
  "occurredAt": "2026-11-12T14:03:22.418Z",
  "workOrderId": "WO-1001",
  "sourceSystem": "SDP",
  "previousStatus": "OPEN",
  "newStatus": "IN_PROGRESS",
  "workOrderVersion": 2,
  "changedBy": "https://okta.local.test/oauth2/workorders|sdp-service"
}
```

- **Duplicates:** `eventId` is unique per event.
- **Out of order:** `workOrderVersion` increases by one per change of a work order. A consumer
  that has applied version N ignores anything `<= N`; a gap tells it an event is still missing.
  Timestamps are informational and not used for ordering.

**Implemented:** the event is written to an `outbox_event` table in the same transaction as the
status change, so an event exists exactly when the change was committed. Nothing relays it yet.

**Delivery to ServiceNow on AWS (design only).**

1. **Relay:** a poller (or CDC with DMS/Debezium) reads unpublished outbox rows in order and
   publishes to an **SNS FIFO topic**, `MessageGroupId = workOrderId`,
   `MessageDeduplicationId = eventId`, and marks the row published after the broker confirms.
   A crash between the two steps republishes the event: delivery is at-least-once by design.
2. **Queue:** an **SQS FIFO queue** subscribed to the topic buffers events for ServiceNow and
   keeps per-work-order ordering. Other consumers get their own queues.
3. **Consumer:** a Lambda (or ECS worker) calls the ServiceNow REST API with a credential from
   Secrets Manager.
4. **Temporary outages:** a failed call leaves the message on the queue; it is retried after
   the visibility timeout with backoff. FIFO blocks only the affected work order's group. After
   N attempts the message goes to a **DLQ** with an alarm and a redrive runbook. During a long
   outage the queue (up to 14 days retention) and the outbox absorb the backlog; consumer
   concurrency is capped to respect ServiceNow rate limits.
5. **Duplicate deliveries:** SNS/SQS FIFO de-duplicate only within 5 minutes, so the consumer
   must be idempotent: upsert by `workOrderId` and apply only if `workOrderVersion` is greater
   than the version stored on the ServiceNow record (or track `eventId` in DynamoDB with a TTL).

## 5. AI-generated decision I reviewed

> **Draft for the candidate to confirm or replace.** This describes a real decision in this
> code base; run the named test and check the reasoning before submitting it as your own.

The AI's first version of token validation used Spring Security's default validators plus an
audience check. Reviewing it, the default timestamp validator turned out to accept a token that
has **no `exp` claim at all**, which would be a non-expiring credential. I had an explicit
"`exp` is required" validator added (`SecurityConfig.tokenValidator`) and validated it with
`AuthenticationTest.rejectsTokenWithoutExpiry`, which signs an otherwise valid token without
`exp` and expects 401. To confirm the test proves something, remove the validator and check that
the test fails.

## 6. Assumptions, gaps and production readiness

**Assumptions**

- The service generates work order ids (`WO-<sequence>`); clients do not supply them.
- `SDP` and `QTSOS` are the only source systems; ServiceNow is a caller, not an owner.
- SDP and QTSOS authenticate through Okta, ServiceNow through Entra.
- A caller without read access to an existing work order gets 403 (not 404). This reveals that
  the id exists; acceptable for trusted internal callers, easy to change.

**Not done**

- No outbox relay or broker integration (design only, as scoped).
- Real Okta/Entra integration is not tested.
- The concurrency test runs against H2. PostgreSQL has the same unique-index blocking semantics,
  but the test should also run against it (Testcontainers) before relying on that.
- No list/search endpoint, pagination or rate limiting.

**Before production**

- PostgreSQL with a managed connection pool; run migrations as a separate deployment step.
- Health/readiness endpoints, metrics (idempotent replays, conflicts, 401/403 rates, outbox
  lag), request correlation ids in logs, structured JSON logging.
- Outbox relay with lag alarms; retention for published outbox rows.
- Partition or batch the idempotency purge at high volume.
- Load test the create path around the lock timeout; dependency and container scanning in CI.
