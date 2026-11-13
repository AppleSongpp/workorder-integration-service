package com.qts.sdp.workorder.controller;

import java.net.URI;
import java.util.regex.Pattern;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qts.sdp.workorder.dto.CreateWorkOrderCommand;
import com.qts.sdp.workorder.dto.CreateWorkOrderRequest;
import com.qts.sdp.workorder.dto.CreationResult;
import com.qts.sdp.workorder.dto.UpdateStatusRequest;
import com.qts.sdp.workorder.dto.WorkOrderResponse;
import com.qts.sdp.workorder.exception.InvalidIdempotencyKeyException;
import com.qts.sdp.workorder.security.Caller;
import com.qts.sdp.workorder.service.WorkOrderService;

@RestController
@RequestMapping("/api/workorders")
public class WorkOrderController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WorkOrderResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateWorkOrderRequest request,
            JwtAuthenticationToken authentication) {
        if (!IDEMPOTENCY_KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        CreationResult result = service.create(Caller.from(authentication), idempotencyKey,
                new CreateWorkOrderCommand(request.sourceSystem(), request.description()));

        // A replay returns exactly what the first request returned: 201 and the original body.
        return ResponseEntity.created(URI.create("/api/workorders/" + result.workOrder().id()))
                .header(IDEMPOTENCY_REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(WorkOrderResponse.from(result.workOrder()));
    }

    @GetMapping("/{id}")
    public WorkOrderResponse get(@PathVariable String id, JwtAuthenticationToken authentication) {
        return WorkOrderResponse.from(service.get(Caller.from(authentication), id));
    }

    @PatchMapping("/{id}/status")
    public WorkOrderResponse changeStatus(@PathVariable String id, @Valid @RequestBody UpdateStatusRequest request,
            JwtAuthenticationToken authentication) {
        return WorkOrderResponse.from(
                service.changeStatus(Caller.from(authentication), id, request.status(), request.version()));
    }
}
