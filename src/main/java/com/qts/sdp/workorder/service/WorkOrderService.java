package com.qts.sdp.workorder.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qts.sdp.workorder.dto.CreateWorkOrderCommand;
import com.qts.sdp.workorder.dto.CreationResult;
import com.qts.sdp.workorder.dto.WorkOrderView;
import com.qts.sdp.workorder.event.WorkOrderStatusChangedEvent;
import com.qts.sdp.workorder.exception.IdempotencyKeyReuseException;
import com.qts.sdp.workorder.exception.RequestInProgressException;
import com.qts.sdp.workorder.exception.VersionConflictException;
import com.qts.sdp.workorder.exception.WorkOrderNotFoundException;
import com.qts.sdp.workorder.model.IdempotencyRecord;
import com.qts.sdp.workorder.model.WorkOrder;
import com.qts.sdp.workorder.model.WorkOrderId;
import com.qts.sdp.workorder.model.WorkOrderStatus;
import com.qts.sdp.workorder.repository.IdempotencyRecordRepository;
import com.qts.sdp.workorder.repository.WorkOrderRepository;
import com.qts.sdp.workorder.security.Caller;

@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    private final WorkOrderRepository workOrders;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final WorkOrderCreator creator;
    private final WorkOrderAccessPolicy accessPolicy;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkOrderService(WorkOrderRepository workOrders, IdempotencyRecordRepository idempotencyRecords,
            WorkOrderCreator creator, WorkOrderAccessPolicy accessPolicy, OutboxWriter outbox,
            ObjectMapper objectMapper, Clock clock) {
        this.workOrders = workOrders;
        this.idempotencyRecords = idempotencyRecords;
        this.creator = creator;
        this.accessPolicy = accessPolicy;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Creates a work order at most once per (caller, idempotency key).
     *
     * <p>Deliberately not transactional: the insert runs in its own transaction inside
     * {@link WorkOrderCreator}. When that transaction loses a race on the unique key it is
     * rolled back, and the winner's result is read here in a fresh transaction.
     *
     * @throws IdempotencyKeyReuseException if the key was used before with a different payload
     * @throws RequestInProgressException   if the first request with this key has not finished yet
     */
    public CreationResult create(Caller caller, String idempotencyKey, CreateWorkOrderCommand command) {
        // Authorize first: a replay must not hand out data to a caller whose grant was removed.
        accessPolicy.requireWrite(caller, command.sourceSystem());
        String requestHash = RequestFingerprint.of(command);

        Optional<IdempotencyRecord> existing = findRecord(caller, idempotencyKey);
        if (existing.isPresent()) {
            return replay(caller, idempotencyKey, existing.get(), requestHash);
        }

        try {
            WorkOrderView created = creator.createAndRecord(caller, idempotencyKey, requestHash, command);
            log.info("Work order created: id={} sourceSystem={} client={} idempotencyKey={}",
                    created.id(), created.sourceSystem(), caller.clientId(), idempotencyKey);
            return new CreationResult(created, false);
        } catch (DataAccessException e) {
            // Expected cause: a concurrent request with the same key. Its insert made ours wait
            // and then fail on the unique constraint, rolling back our work order. Whatever the
            // exact failure, a committed record for this key is the authoritative result.
            Optional<IdempotencyRecord> winner = findRecord(caller, idempotencyKey);
            if (winner.isPresent()) {
                return replay(caller, idempotencyKey, winner.get(), requestHash);
            }
            if (e instanceof ConcurrencyFailureException) {
                // Lock timeout or deadlock while the other request is still running.
                throw new RequestInProgressException(e);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public WorkOrderView get(Caller caller, String externalId) {
        WorkOrder workOrder = load(externalId);
        accessPolicy.requireRead(caller, workOrder.getSourceSystem());
        return WorkOrderView.of(workOrder);
    }

    /**
     * Changes the status and records a {@link WorkOrderStatusChangedEvent} in the same transaction.
     * Requesting the status the work order already has is a successful no-op (no version bump,
     * no event), which makes a retried PATCH safe.
     *
     * @param expectedVersion optional; when given, the change is rejected unless the work order is
     *                        still at this version
     */
    @Transactional
    public WorkOrderView changeStatus(Caller caller, String externalId, WorkOrderStatus target,
            Long expectedVersion) {
        WorkOrder workOrder = load(externalId);
        accessPolicy.requireWrite(caller, workOrder.getSourceSystem());

        if (expectedVersion != null && expectedVersion != workOrder.getVersion()) {
            throw new VersionConflictException(expectedVersion, workOrder.getVersion());
        }
        WorkOrderStatus previous = workOrder.getStatus();
        if (previous == target) {
            return WorkOrderView.of(workOrder);
        }

        Instant now = clock.instant();
        workOrder.changeStatus(target, now);
        // Flush to obtain the incremented version for the event. A concurrent update of the same
        // row fails here with an optimistic locking error (mapped to 409), rolling back both.
        workOrders.saveAndFlush(workOrder);
        outbox.append(new WorkOrderStatusChangedEvent(UUID.randomUUID(), WorkOrderStatusChangedEvent.TYPE,
                WorkOrderStatusChangedEvent.SCHEMA_VERSION, now, workOrder.getExternalId(),
                workOrder.getSourceSystem(), previous, target, workOrder.getVersion(), caller.clientId()));

        log.info("Work order status changed: id={} from={} to={} version={} client={}",
                workOrder.getExternalId(), previous, target, workOrder.getVersion(), caller.clientId());
        return WorkOrderView.of(workOrder);
    }

    private WorkOrder load(String externalId) {
        return WorkOrderId.parse(externalId)
                .flatMap(workOrders::findById)
                .orElseThrow(WorkOrderNotFoundException::new);
    }

    private Optional<IdempotencyRecord> findRecord(Caller caller, String idempotencyKey) {
        return idempotencyRecords.findByClientIdAndIdempotencyKey(caller.clientId(), idempotencyKey);
    }

    private CreationResult replay(Caller caller, String idempotencyKey, IdempotencyRecord stored,
            String requestHash) {
        if (!stored.getRequestHash().equals(requestHash)) {
            log.warn("Idempotency key reused with a different payload: client={} idempotencyKey={}",
                    caller.clientId(), idempotencyKey);
            throw new IdempotencyKeyReuseException();
        }
        log.info("Idempotent replay: id={} client={} idempotencyKey={}",
                WorkOrderId.format(stored.getWorkOrderId()), caller.clientId(), idempotencyKey);
        try {
            return new CreationResult(objectMapper.readValue(stored.getResponseBody(), WorkOrderView.class), true);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }
}
