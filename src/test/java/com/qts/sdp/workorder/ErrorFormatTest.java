package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * Errors raised outside the controllers still use the API's problem format and reveal nothing
 * about the framework or the request. Sent over real HTTP: the URLs in the first test are
 * rejected by Tomcat itself, which MockMvc does not involve.
 */
class ErrorFormatTest extends IntegrationTest {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @ParameterizedTest
    @ValueSource(strings = {"/api/workorders/WO-1%2F..", "/api/workorders/WO-1001%00"})
    void urlRejectedByTomcatIsAProblemDocumentNotAnHtmlPage(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode problem = json(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("title").asString()).isEqualTo("Bad Request");
        assertThat(problem.get("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(response.body()).doesNotContainIgnoringCase("html").doesNotContainIgnoringCase("tomcat");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/workorders/WO-1001/", "/api/workorders/", "/api/workorders/WO-1001/a/b"})
    void pathWithoutAnEndpointIsAPlainNotFound(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode problem = json(response.body());
        assertThat(problem.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(problem.get("detail").asString()).isEqualTo("The requested resource was not found.");
        assertThat(response.body()).doesNotContain("static resource");
    }

    @Test
    void applicationErrorsAreStillWrittenByTheApplication() throws Exception {
        HttpResponse<String> response = get("/api/workorders/WO-999999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response.body()).get("code").asString()).isEqualTo("WORK_ORDER_NOT_FOUND");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", bearer(TestCaller.SDP))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
