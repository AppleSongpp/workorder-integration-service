package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;

import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * Concurrent status changes of one work order must be applied exactly once: one version bump
 * and one outbox event, no matter how many requests race. Uses real HTTP connections against
 * the running server so that requests are handled by different server threads and database
 * transactions at the same time.
 */
class ConcurrentStatusChangeTest extends IntegrationTest {

    private static final int PARALLEL_REQUESTS = 8;

    /**
     * Every request states the version it read. Only one of them can still be right once the
     * first has committed: the rest fail either the explicit version check (they read after the
     * winner committed) or the optimistic lock at flush (they read before it).
     */
    @RepeatedTest(5)
    void concurrentChangesWithTheSameExpectedVersionHaveExactlyOneWinner() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        List<HttpResponse<String>> responses = patchInParallel(id, "{\"status\":\"IN_PROGRESS\",\"version\":1}");

        long succeeded = responses.stream().filter(response -> response.statusCode() == 200).count();
        assertThat(succeeded).isEqualTo(1);
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 200) {
                assertThat(json(response.body()).get("version").asLong()).isEqualTo(2);
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertThat(json(response.body()).get("code").asString()).isEqualTo("VERSION_CONFLICT");
            }
        }
        assertAppliedExactlyOnce(id);
    }

    /**
     * Without an expected version a request that arrives after the winner sees the target
     * status already set and succeeds as a no-op, so several 200s are fine. A request that lost
     * the race mid-flight gets 409. Either way the change itself happens once.
     */
    @RepeatedTest(5)
    void concurrentChangesWithoutAVersionAreAppliedOnce() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        List<HttpResponse<String>> responses = patchInParallel(id, "{\"status\":\"IN_PROGRESS\"}");

        long succeeded = 0;
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 200) {
                succeeded++;
                assertThat(json(response.body()).get("status").asString()).isEqualTo("IN_PROGRESS");
                assertThat(json(response.body()).get("version").asLong()).isEqualTo(2);
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertThat(json(response.body()).get("code").asString()).isEqualTo("VERSION_CONFLICT");
            }
        }
        assertThat(succeeded).isGreaterThanOrEqualTo(1);
        assertAppliedExactlyOnce(id);
    }

    private void assertAppliedExactlyOnce(String id) {
        Long version = jdbc.queryForObject("select version from work_order where id = ?", Long.class,
                Long.parseLong(id.substring("WO-".length())));
        assertThat(version).isEqualTo(2);
        Integer events = jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?",
                Integer.class, id);
        assertThat(events).isEqualTo(1);
    }

    private List<HttpResponse<String>> patchInParallel(String id, String body) throws Exception {
        String authorization = bearer(TestCaller.SDP);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        CountDownLatch ready = new CountDownLatch(PARALLEL_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create("http://localhost:" + port + "/api/workorders/" + id + "/status"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build();
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
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
}
