package com.qts.sdp.workorder.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders authentication and authorization failures raised by the security filter chain.
 * The client always gets the same generic body: why a token was rejected (expired, bad
 * signature, unknown issuer, ...) is useful to an attacker, so it is only written to the log.
 * Tokens themselves are never logged.
 */
@Component
public class SecurityProblemWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemWriter.class);

    private final ObjectMapper objectMapper;

    public SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        boolean tokenPresent = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        log.warn("Authentication failed: method={} path={} tokenPresent={} reason=\"{}\"",
                request.getMethod(), request.getRequestURI(), tokenPresent, exception.getMessage());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, tokenPresent ? "Bearer error=\"invalid_token\"" : "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid bearer access token is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        log.warn("Access denied by filter chain: method={} path={}", request.getMethod(), request.getRequestURI());
        write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this operation.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
