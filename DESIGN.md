# Design

## 1. Status transition rules

`OPEN → IN_PROGRESS → COMPLETED` (terminal), plus `IN_PROGRESS → OPEN` to release work back to
the queue. Nothing else is allowed.

- Rules live in `WorkOrderStatus`; the `WorkOrder` entity enforces them, so no caller can bypass them.
- An invalid transition returns `409 INVALID_STATUS_TRANSITION` with the current and allowed
  statuses: the request is well-formed and only conflicts with the resource's state, hence 409, not 422.
- Setting the status a work order already has is a successful no-op (no version bump, no event),
  which makes a retried `PATCH` safe without an idempotency key.
- Every change increments `version` (JPA `@Version`). Callers may send the version they last
  read; a stale value, or losing a concurrent update, returns `409 VERSION_CONFLICT`
  (`ConcurrentStatusChangeTest`).

## 2. Idempotency

**Scope.** A key belongs to the authenticated caller (token issuer + subject) and to
`POST /api/workorders`. Callers cannot affect or probe each other's keys. The header is
required: every create in a system-to-system API should be retry-safe.

**Storage.** One `idempotency_record` row per successful creation: caller, key, SHA-256 of the
parsed request fields, work order id, original response. `UNIQUE (client_id, idempotency_key)`
is the guard (`IdempotencyService`). Failed requests (400, 401, 403) store nothing, so the key stays usable.

| Situation | Result |
|---|---|
| First request | `201`, `Idempotency-Replayed: false` |
| Same key, same payload | Original `201` and body, `Idempotency-Replayed: true`, even if the work order changed since |
| Same key, different payload | `409 IDEMPOTENCY_KEY_REUSED` |
| Same key, first request still running when the wait times out | `409 REQUEST_IN_PROGRESS` + `Retry-After` |

**Concurrency.** The work order and its record are inserted in one transaction
(`WorkOrderCreator`). A racing request with the same key blocks on the unique index until the
first commits, then fails and rolls back, including its own work order.
`WorkOrderService.create` then reads the winner's record and replays it. Because both rows
commit atomically, a key either points to a finished work order or does not exist; there is no
"in progress" marker for a crash to leave behind. The cost: a loser waits (up to the 10 s lock
timeout) instead of failing fast, and a sequence number is skipped. I chose this over an
in-process lock because it stays correct with several instances, and over "claim the key, then
create" because that needs recovery for claims whose owner died.

**Restarts and retention.** Records share the work orders' database, so they survive restarts
(`RestartPersistenceTest`). They are kept 24 hours and purged every 15 minutes, so the
guarantee is "at least 24 hours"; afterwards the same key creates a new work order.

## 3. Authentication and authorization

**Authentication.** An OAuth2 resource server trusts two simulated issuers, each with its own
RSA key. The token's `iss` selects the issuer (unknown issuers are rejected), and the token is
validated with that issuer's key only: RS256 signature, `exp` (required), `nbf`, exact `iss`,
`aud = api://workorders`, non-empty `sub`.

**Authorization.** Permissions come only from the validated token, as
`workorders:<read|write>:<SOURCE_SYSTEM>` in the issuer's permissions claim.
`WorkOrderAccessPolicy` checks them against the source system of the data: the requested owner
on create, the **stored** owner on read and status change. Nothing is taken from query
parameters or custom headers. Failures return a generic 401 or 403; the reason is only logged,
and tokens never are.

**Two deliberate trade-offs.**

- *Case-insensitive permission strings.* IdPs and admins are inconsistent about case, and a
  mismatch would lock a caller out with no visible reason. Access cannot widen: the value must
  still name a known action and source system.
- *Existence leak.* Without read access a caller gets `403` for an existing work order and `404`
  for a missing one, so ids (sequential) can be enumerated, but no content. Callers are trusted
  internal services, and a clear "not allowed" is easier to diagnose. If existence is
  sensitive, return `404` for both in `WorkOrderService.get`.

**Adapting to real Okta and Entra** is mostly configuration; each issuer entry already accepts
`jwk-set-uri` instead of a key file, which also brings key rotation. Okta carries permissions
as custom scopes in `scp`, Entra as app roles in `roles`. Still to decide: the caller-id claim
per issuer (Entra needs `azp` or `oid`, not `sub` alone). That path is untested here.

## 4. `WorkOrderStatusChanged` event

```json
{ "eventId": "6f1c1c0e-6d1b-4a55-9d3b-0b6c1d2f9a11", "eventType": "WorkOrderStatusChanged",
  "schemaVersion": 1, "occurredAt": "2026-11-12T14:03:22.418Z", "workOrderId": "WO-1001",
  "sourceSystem": "SDP", "previousStatus": "OPEN", "newStatus": "IN_PROGRESS",
  "workOrderVersion": 2, "changedBy": "https://okta.local.test/oauth2/workorders|sdp-service" }
```

- **Duplicates:** consumers de-duplicate on `eventId`; the version rule below drops them too.
  SNS/SQS FIFO de-duplication only covers 5 minutes, so it cannot be relied on alone.
- **Out of order:** `workOrderVersion` increases by one per change. A consumer that applied
  version N ignores anything `<= N`; a gap means an event is still missing. Timestamps are
  informational only.

**Implemented:** the event is written to `outbox_event` in the same transaction as the status
change, so it exists exactly when the change committed. Nothing relays it yet.

**Delivery to ServiceNow on AWS (design only).**

1. **Relay:** a poller (or CDC) publishes unpublished outbox rows to an SNS FIFO topic with
   `MessageGroupId = workOrderId` and `MessageDeduplicationId = eventId`, then marks them
   published. A crash in between republishes: delivery is at-least-once.
2. **Queue and consumer:** an SQS FIFO queue per consumer keeps per-work-order order; a Lambda
   or ECS worker calls the ServiceNow REST API with a credential from Secrets Manager, upserting
   by `workOrderId`.
3. **Outages:** a failed call leaves the message on the queue for retry with backoff; FIFO
   blocks only that work order's group. After N attempts it goes to a DLQ with an alarm. The
   queue (14 days) and the outbox absorb a long outage.

## 5. AI-generated decision I reviewed

AI tool is used to generate the initial implementation and endpoints tests. Then I reviewed the failures
to make sure the code is correct. The clearest defect was Jackson's default enum handling: 
`{"sourceSystem": 0}` was accepted as `SDP`, since the number was treated as an enumeration ordinal. 
Similar values could be accepted for PATCH status. The first systematic POST test passed 92/93 checks.
The reason I chose to reject numeric enumeration values is because the API contract uses named value and
 enum ordinals are unstable. With assistance from AI, I configured Jackson to fail on numbers for enums 
and added POST/PATCH regression tests in `WorkOrderApiTest`. The updated build passed 113 test cases 
and the POST test suite all passed after restart and refactoring.

After review the code structure, I decided to separate idempotency concerns from WorkOrderService`, centralise 
error-body creation, move key-format validation out of the controller, and narrow database-conflict exception handling. 
The target is to improve maintainability by having clearer responsibilities and more consistent error handling.


## 6. Assumptions, gaps and production readiness

**Assumptions and scope.** Work-order IDs (`WO-<sequence>`) are generated server-side; 
SDP and QTSOS are the only owners, ServiceNow is a read-only caller. I chose
**file-based H2** over PostgreSQL for simpler local setup and restart persistence. Real Okta/Entra 
connections, AWS deployment, and the event relay are out of scope.

**Known gaps.** Concurrency was exercised against H2,not PostgreSQL; Idempotency expiry and purge are covered by 
an automated test, but not under load. Some lenient JSON input is still accepted: a number or boolean for `description`, duplicate fields, quoted numeric versions, and padded status strings (the last one a deliberate choice). The stricter 
cases found in testing (numeric enums, fractional versions, encoded-path and trailing-slash errors) are fixed and have regression tests.                                    

**Before production:** PostgreSQL with versioned migrations and test containers concurrency tests; real issuer claims 
verified and keys rotated through JWKS; stricter JSON validation; the outbox relay, ServiceNow consumer, DLQ and alerting; readiness checks, metrics, correlated structured logs, CI security scanning and rate limiting.
