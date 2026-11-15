package com.qts.sdp.workorder.dto;

/**
 * @param workOrder the work order as it was when first created
 * @param replayed  true if this request repeated an earlier one and nothing new was created
 */
public record CreationResult(WorkOrderView workOrder, boolean replayed) {
}
