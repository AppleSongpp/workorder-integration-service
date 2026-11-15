package com.qts.sdp.workorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.qts.sdp.workorder.model.WorkOrderStatus;

/**
 * @param status  the status to move to
 * @param version optional optimistic-concurrency check: the version the caller last saw
 */
public record UpdateStatusRequest(
        @NotNull(message = "is required") WorkOrderStatus status,
        @Positive(message = "must be positive") Long version) {
}
