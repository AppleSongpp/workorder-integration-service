package com.qts.sdp.workorder.config;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.qts.sdp.workorder.exception.Problems;

/**
 * Renders errors that Tomcat raises itself as a problem document instead of its HTML page.
 * Some requests never reach Spring: Tomcat rejects a URL with an encoded slash ({@code %2F})
 * or a null byte ({@code %00}) with 400 before any filter or controller runs, so neither
 * {@code ApiExceptionHandler} nor {@code SecurityProblemWriter} can format the response.
 *
 * <p>The body is fixed text per status. Nothing from the request is echoed.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    private final ObjectMapper objectMapper;

    public ProblemErrorReportValve(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int statusCode = response.getStatus();
        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        try {
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("utf-8");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body(statusCode));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException | JacksonException e) {
            // The client is gone or the response is already being written; nothing to report to.
        }
    }

    private String body(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = statusCode < 500 ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = status == HttpStatus.BAD_REQUEST
                ? "The request is malformed."
                : "The request could not be processed.";
        return objectMapper.writeValueAsString(Problems.of(status, status.name(), detail));
    }
}
