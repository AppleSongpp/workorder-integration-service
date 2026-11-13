package com.qts.sdp.workorder.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 problem document with a stable, machine-readable
 * {@code code}. Details that describe internals (stack traces, SQL, parser messages) stay in
 * the log. Standard Spring MVC errors (unsupported media type, missing header, ...) are
 * handled by the base class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WorkOrderNotFoundException.class)
    ProblemDetail notFound(WorkOrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "WORK_ORDER_NOT_FOUND", "Work order not found.");
    }

    @ExceptionHandler(SourceSystemAccessDeniedException.class)
    ProblemDetail forbidden(SourceSystemAccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this operation.");
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail invalidTransition(InvalidStatusTransitionException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", e.getMessage() + ".");
        problem.setProperty("currentStatus", e.getFrom());
        problem.setProperty("allowedStatuses", e.getFrom().allowedTargets().stream().sorted().toList());
        return problem;
    }

    @ExceptionHandler(VersionConflictException.class)
    ProblemDetail versionConflict(VersionConflictException e) {
        return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", e.getMessage() + ". Re-read it and retry.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "The work order was modified concurrently. Re-read it and retry.");
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail idempotencyKeyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request payload. "
                        + "Use a new key for a new request.");
    }

    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<ProblemDetail> requestInProgress(RequestInProgressException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed. Retry shortly with the same key.");
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKeyException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", e.getMessage() + ".");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .sorted((a, b) -> a.get("field").compareTo(b.get("field")))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The parser message can echo input and class names; keep it out of the response.
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body is missing, is not valid JSON, or contains an unknown field or value.");
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /**
     * A path that matches no endpoint, e.g. a trailing slash after the id. The default detail
     * names the framework's static resource lookup and repeats the path; ours does neither.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(e, noSuchEndpoint(), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(e, noSuchEndpoint(), headers, status, request);
    }

    private static ProblemDetail noSuchEndpoint() {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.");
    }

    /** Adds a {@code code} to the problem documents produced by the base class. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            HttpStatus resolved = HttpStatus.resolve(status.value());
            problem.setProperty("code", resolved != null ? resolved.name() : "ERROR");
        }
        return response;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
