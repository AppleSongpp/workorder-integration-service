package com.qts.sdp.workorder.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qts.sdp.workorder.model.WorkOrder;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
}
