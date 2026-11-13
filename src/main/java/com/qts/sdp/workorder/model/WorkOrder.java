package com.qts.sdp.workorder.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.qts.sdp.workorder.exception.InvalidStatusTransitionException;

@Entity
@Table(name = "work_order")
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "work_order_seq")
    @SequenceGenerator(name = "work_order_seq", sequenceName = "work_order_seq", allocationSize = 1)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_system", nullable = false, updatable = false, length = 32)
    private SourceSystem sourceSystem;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private WorkOrderStatus status;

    /** Optimistic lock; starts at 1 and increases by one on every change. Also orders events. */
    @Version
    @Column(nullable = false)
    private long version = 1;

    @Column(name = "created_by", nullable = false, updatable = false, length = 512)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkOrder() {
        // JPA
    }

    public static WorkOrder open(SourceSystem sourceSystem, String description, String createdBy, Instant now) {
        WorkOrder workOrder = new WorkOrder();
        workOrder.sourceSystem = sourceSystem;
        workOrder.description = description;
        workOrder.status = WorkOrderStatus.OPEN;
        workOrder.createdBy = createdBy;
        workOrder.createdAt = now;
        workOrder.updatedAt = now;
        return workOrder;
    }

    /**
     * Moves the work order to {@code target}.
     *
     * @throws InvalidStatusTransitionException if the lifecycle does not allow the move
     */
    public void changeStatus(WorkOrderStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return WorkOrderId.format(id);
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public String getDescription() {
        return description;
    }

    public WorkOrderStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }
}
