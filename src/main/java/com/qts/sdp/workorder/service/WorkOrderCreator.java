package com.qts.sdp.workorder.service;

import java.time.Clock;
import java.time.Instant;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.qts.sdp.workorder.config.IdempotencyProperties;
import com.qts.sdp.workorder.dto.CreateWorkOrderCommand;
import com.qts.sdp.workorder.dto.WorkOrderView;
import com.qts.sdp.workorder.model.IdempotencyRecord;
import com.qts.sdp.workorder.model.WorkOrder;
import com.qts.sdp.workorder.repository.IdempotencyRecordRepository;
import com.qts.sdp.workorder.repository.WorkOrderRepository;
import com.qts.sdp.workorder.security.Caller;

/**
 * The single transaction that creates a work order together with its idempotency record.
 * Either both rows are committed or neither is, so a stored key always points at a finished
 * result and there is no "in progress" state to recover after a crash.
 *
 * <p>Kept in its own bean so the caller ({@link WorkOrderService}) can react to a failed
 * transaction from outside of it.
 */
@Component
public class WorkOrderCreator {

    private final WorkOrderRepository workOrders;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkOrderCreator(WorkOrderRepository workOrders, IdempotencyRecordRepository idempotencyRecords,
            IdempotencyProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.workOrders = workOrders;
        this.idempotencyRecords = idempotencyRecords;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @throws org.springframework.dao.DataAccessException if another transaction committed the
     *         same (caller, key) first, or is still holding it; this transaction is then rolled
     *         back, including its work order
     */
    @Transactional
    public WorkOrderView createAndRecord(Caller caller, String idempotencyKey, String requestHash,
            CreateWorkOrderCommand command) {
        Instant now = clock.instant();
        WorkOrder workOrder = workOrders.save(
                WorkOrder.open(command.sourceSystem(), command.description(), caller.clientId(), now));
        WorkOrderView view = WorkOrderView.of(workOrder);

        // Flush now so a duplicate key surfaces here, as a typed exception, and not at commit.
        // A concurrent transaction holding the same key makes this statement wait until that
        // transaction ends, then fail on the unique constraint.
        idempotencyRecords.saveAndFlush(new IdempotencyRecord(caller.clientId(), idempotencyKey, requestHash,
                workOrder.getId(), HttpStatus.CREATED.value(), toJson(view), now,
                now.plus(properties.retention())));
        return view;
    }

    private String toJson(WorkOrderView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize work order " + view.id(), e);
        }
    }
}
