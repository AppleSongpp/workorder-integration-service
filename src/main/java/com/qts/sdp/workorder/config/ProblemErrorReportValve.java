package com.qts.sdp.workorder.config;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Renders errors that Tomcat raises itself as a problem document instead of its HTML page.
 * Some requests never reach Spring: Tomcat rejects a URL with an encoded slash ({@code %2F})
 * or a null byte ({@code %00}) with 400 before any filter or controller runs, so neither
 * {@code ApiExceptionHandler} nor {@code SecurityProblemWriter} can format the response.
 *
 * <p>The body is fixed text per status. Nothing from the request is echoed.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

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
        } catch (IOException | IllegalStateException e) {
            // The client is gone or the response is already being written; nothing to report to.
        }
    }

    /** Same shape as the problem documents written by the application: detail, status, title, code. */
    static String body(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        String title = status != null ? status.getReasonPhrase() : "Error";
        String code = status != null ? status.name() : "ERROR";
        String detail = statusCode == 400 ? "The request is malformed." : "The request could not be processed.";
        return "{\"detail\":\"" + detail + "\",\"status\":" + statusCode
                + ",\"title\":\"" + title + "\",\"code\":\"" + code + "\"}";
    }
}
