package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * Idempotency must survive an application restart: start the service on a file database,
 * create a work order, stop the service, start a new instance on the same file, repeat the
 * request.
 */
class RestartPersistenceTest {

    @TempDir
    Path databaseDirectory;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void idempotencyKeysAndWorkOrdersSurviveARestart() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"sourceSystem\":\"SDP\",\"description\":\"Survives restart\"}";
        String authorization = "Bearer " + TestIdps.tokenFor(TestCaller.SDP);

        HttpResponse<String> beforeRestart;
        try (ConfigurableApplicationContext firstInstance = startApplication()) {
            beforeRestart = post(firstInstance, authorization, key, body);
            assertThat(beforeRestart.statusCode()).isEqualTo(201);
            assertThat(beforeRestart.headers().firstValue("Idempotency-Replayed")).contains("false");
        }

        try (ConfigurableApplicationContext secondInstance = startApplication()) {
            HttpResponse<String> afterRestart = post(secondInstance, authorization, key, body);
            assertThat(afterRestart.statusCode()).isEqualTo(201);
            assertThat(afterRestart.headers().firstValue("Idempotency-Replayed")).contains("true");
            assertThat(afterRestart.body()).isEqualTo(beforeRestart.body());

            HttpResponse<String> conflicting = post(secondInstance, authorization, key,
                    "{\"sourceSystem\":\"SDP\",\"description\":\"Something else\"}");
            assertThat(conflicting.statusCode()).isEqualTo(409);

            String location = beforeRestart.headers().firstValue("Location").orElseThrow();
            HttpResponse<String> fetched = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl(secondInstance) + location))
                            .header("Authorization", authorization).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(fetched.statusCode()).isEqualTo(200);
            assertThat(fetched.body()).isEqualTo(beforeRestart.body());
        }
    }

    private ConfigurableApplicationContext startApplication() {
        String databaseFile = databaseDirectory.resolve("workorders").toAbsolutePath().toString().replace('\\', '/');
        return new SpringApplicationBuilder(WorkOrderServiceApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + databaseFile,
                "--workorders.security.issuers.okta.public-key-location=" + TestIdps.oktaPublicKeyLocation(),
                "--workorders.security.issuers.entra.public-key-location=" + TestIdps.entraPublicKeyLocation());
    }

    private HttpResponse<String> post(ConfigurableApplicationContext application, String authorization, String key,
            String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(application) + "/api/workorders"))
                .header("Authorization", authorization)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String baseUrl(ConfigurableApplicationContext application) {
        return "http://localhost:" + application.getEnvironment().getProperty("local.server.port");
    }
}
