package com.qts.sdp.workorder.service;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.qts.sdp.workorder.dto.CreateWorkOrderCommand;
import com.qts.sdp.workorder.dto.WorkOrderView;
import com.qts.sdp.workorder.model.IdempotencyKey;
import com.qts.sdp.workorder.model.WorkOrder;
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
    private final IdempotencyService idempotency;
    private final Clock clock;

    public WorkOrderCreator(WorkOrderRepository workOrders, IdempotencyService idempotency, Clock clock) {
        this.workOrders = workOrders;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if another transaction
     *         committed the same (caller, key) first
     * @throws org.springframework.dao.ConcurrencyFailureException     if another transaction is
     *         still holding the same (caller, key) when the lock wait times out
     *         <p>In both cases this transaction is rolled back, including its work order.
     */
    @Transactional
    public WorkOrderView createAndRecord(Caller caller, IdempotencyKey key, String requestHash,
            CreateWorkOrderCommand command) {
        WorkOrder workOrder = workOrders.save(
                WorkOrder.open(command.sourceSystem(), command.description(), caller.clientId(), clock.instant()));
        WorkOrderView view = WorkOrderView.of(workOrder);
        idempotency.record(caller, key, requestHash, workOrder.getId(), view);
        return view;
    }
}
