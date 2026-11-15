package com.qts.sdp.workorder.dto;

import com.qts.sdp.workorder.model.SourceSystem;

public record CreateWorkOrderCommand(SourceSystem sourceSystem, String description) {
}
