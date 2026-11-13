package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;

import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * Concurrent requests with the same Idempotency-Key must not create duplicate work orders.
 * Uses real HTTP connections against the running server so that requests are handled by
 * different server threads and database transactions at the same time.
 */
class ConcurrentCreationTest extends IntegrationTest {

    private static final int PARALLEL_REQUESTS = 8;

    @RepeatedTest(5)
    void concurrentRequestsWithTheSameKeyCreateExactlyOneWorkOrder() throws Exception {
        String key = newKey();
        String description = "concurrent-" + key;
        List<HttpResponse<String>> responses = sendInParallel(key, sameBody(createBody("SDP", description)));

        // The invariant: one row, no matter how the race went.
        assertThat(countWorkOrdersWithDescription(description)).isEqualTo(1);
        assertThat(countIdempotencyRecords(key)).isEqualTo(1);

        // Every caller got the same answer. (409 REQUEST_IN_PROGRESS would also be a correct
        // answer for a request that gave up waiting, but with a 10s lock timeout it is not
        // expected here, so the test insists on the stronger outcome.)
        Set<String> ids = new HashSet<>();
        int createdNow = 0;
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            ids.add(json(response.body()).get("id").asString());
            if ("false".equals(response.headers().firstValue("Idempotency-Replayed").orElseThrow())) {
                createdNow++;
            }
        }
        assertThat(ids).hasSize(1);
        assertThat(createdNow).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentRequestsWithTheSameKeyButDifferentPayloadsCreateExactlyOneWorkOrder() throws Exception {
        String key = newKey();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < PARALLEL_REQUESTS; i++) {
            bodies.add(createBody("SDP", "concurrent-mixed-" + key + "-" + i));
        }
        List<HttpResponse<String>> responses = sendInParallel(key, bodies);

        Integer rows = jdbc.queryForObject("select count(*) from work_order where description like ?",
                Integer.class, "concurrent-mixed-" + key + "-%");
        assertThat(rows).isEqualTo(1);
        assertThat(countIdempotencyRecords(key)).isEqualTo(1);

        long created = responses.stream().filter(response -> response.statusCode() == 201).count();
        long conflicts = responses.stream().filter(response -> response.statusCode() == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(PARALLEL_REQUESTS - 1);
    }

    private static List<String> sameBody(String body) {
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < PARALLEL_REQUESTS; i++) {
            bodies.add(body);
        }
        return bodies;
    }

    private List<HttpResponse<String>> sendInParallel(String key, List<String> bodies) throws Exception {
        String authorization = bearer(TestCaller.SDP);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        CountDownLatch ready = new CountDownLatch(bodies.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String body : bodies) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/workorders"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", authorization)
                        .header("Idempotency-Key", key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                Callable<HttpResponse<String>> call = () -> {
                    ready.countDown();
                    go.await();
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                };
                futures.add(pool.submit(call));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private int countIdempotencyRecords(String key) {
        Integer count = jdbc.queryForObject("select count(*) from idempotency_record where idempotency_key = ?",
                Integer.class, key);
        return count == null ? 0 : count;
    }
}
