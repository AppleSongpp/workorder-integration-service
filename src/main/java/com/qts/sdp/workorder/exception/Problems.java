package com.qts.sdp.workorder.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The one place that builds the API's error body (RFC 9457 problem document plus a stable,
 * machine-readable {@code code}). Used by everything that can answer with an error: the
 * controller advice, the security filter chain and the Tomcat error valve.
 */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
