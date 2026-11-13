package com.qts.sdp.workorder.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.qts.sdp.workorder.exception.InvalidStatusTransitionException;

class WorkOrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @CsvSource({
            "OPEN,        OPEN,        false",
            "OPEN,        IN_PROGRESS, true",
            "OPEN,        COMPLETED,   false",
            "IN_PROGRESS, OPEN,        true",
            "IN_PROGRESS, IN_PROGRESS, false",
            "IN_PROGRESS, COMPLETED,   true",
            "COMPLETED,   OPEN,        false",
            "COMPLETED,   IN_PROGRESS, false",
            "COMPLETED,   COMPLETED,   false"
    })
    void transitionRules(WorkOrderStatus from, WorkOrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void newWorkOrderIsOpenAtVersionOne() {
        WorkOrder workOrder = WorkOrder.open(SourceSystem.SDP, "Cooling unit", "tester", Instant.now());

        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.OPEN);
        assertThat(workOrder.getVersion()).isEqualTo(1);
    }

    @Test
    void entityRejectsInvalidTransitionAndKeepsItsStatus() {
        WorkOrder workOrder = WorkOrder.open(SourceSystem.SDP, "Cooling unit", "tester", Instant.now());

        assertThatThrownBy(() -> workOrder.changeStatus(WorkOrderStatus.COMPLETED, Instant.now()))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.OPEN);
    }

    @Test
    void parsesOnlyWellFormedIds() {
        assertThat(WorkOrderId.parse("WO-1001")).contains(1001L);
        assertThat(WorkOrderId.format(1001)).isEqualTo("WO-1001");
        assertThat(WorkOrderId.parse("1001")).isEmpty();
        assertThat(WorkOrderId.parse("WO-0")).isEmpty();
        assertThat(WorkOrderId.parse("WO-abc")).isEmpty();
        assertThat(WorkOrderId.parse("wo-1001")).isEmpty();
        assertThat(WorkOrderId.parse("WO-99999999999999999999999")).isEmpty();
        assertThat(WorkOrderId.parse(null)).isEmpty();
    }
}
