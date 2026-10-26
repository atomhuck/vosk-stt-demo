package ru.example.interview.web;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"interview.events.token=test-internal-token-which-is-long-enough-32",
                "interview.reports-dir=${java.io.tmpdir}/analytics-websocket-test"})
class InterviewEventsWebSocketTest {
    @LocalServerPort
    private int port;

    @Test
    void starts_an_interview_and_rejects_out_of_order_stt_events() throws Exception {
        var rejected = assertThrows(java.util.concurrent.ExecutionException.class, () -> HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/interview-events"), new Events())
                .get(5, TimeUnit.SECONDS));
        assertTrue(rejected.getCause() instanceof WebSocketHandshakeException);
        assertEquals(401, ((WebSocketHandshakeException) rejected.getCause()).getResponse().statusCode());

        Events events = new Events();
        WebSocket socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder().header("X-Internal-Token", "test-internal-token-which-is-long-enough-32")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/interview-events"), events)
                .get(5, TimeUnit.SECONDS);
        try {
            ObjectMapper mapper = new ObjectMapper();
            socket.sendText("{\"type\":\"start\",\"sessionId\":\"test-session\",\"levels\":[\"INTERN\"]}", true)
                    .get(5, TimeUnit.SECONDS);
            JsonNode question = mapper.readTree(events.next());
            assertEquals("question", question.path("type").asText());
            assertEquals("test-session", question.path("sessionId").asText());
            assertEquals(1, question.path("sequence").asLong());
            assertEquals(0, question.path("audioSample").asLong());

            socket.sendText("{\"type\":\"ready\",\"sessionId\":\"test-session\",\"sequence\":2}", true)
                    .get(5, TimeUnit.SECONDS);
            JsonNode error = mapper.readTree(events.next());
            assertEquals("error", error.path("type").asText());
            assertEquals("test-session", error.path("sessionId").asText());
            assertEquals(2, error.path("sequence").asLong());
            assertTrue(error.path("message").asText().contains("Пропущено событие STT"));
            assertTrue(events.closed.await(5, TimeUnit.SECONDS));
            assertEquals(1008, events.closedCode);
        } finally {
            socket.abort();
        }
    }

    @Test
    void candidate_events_can_be_refined_before_the_xlsx_report() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Events events = new Events();
        WebSocket socket = connect(events);
        try {
            send(socket, mapper, Map.of("type", "start", "sessionId", "report-session", "levels", new String[]{"INTERN"}));
            assertTrue(events.next().contains("\"type\":\"question\""));
            send(socket, mapper, Map.of("type", "ready", "sessionId", "report-session", "sequence", 1));

            long sequence = 2;
            send(socket, mapper, Map.of("type", "frame", "sessionId", "report-session", "sequence", sequence++,
                    "samples", 800, "rms", 900, "asrMs", 2.0, "asrType", "final",
                    "text", "Java — объектно ориентированный язык. JVM исполняет байткод, а JDK содержит компилятор."));
            send(socket, mapper, Map.of("type", "frame", "sessionId", "report-session", "sequence", sequence++,
                    "samples", 800, "rms", 900, "asrMs", 1.0, "asrType", "", "text", ""));

            String decisionJson = "";
            for (int i = 0; i < 45 && !decisionJson.contains("\"type\":\"decision\""); i++) {
                send(socket, mapper, Map.of("type", "frame", "sessionId", "report-session", "sequence", sequence++,
                        "samples", 800, "rms", 0, "asrMs", 0, "asrType", "", "text", ""));
                while (events.hasMessage()) {
                    String candidate = events.next();
                    if (candidate.contains("\"type\":\"decision\"")) decisionJson = candidate;
                }
            }
            if (decisionJson.isBlank()) {
                while (!decisionJson.contains("\"type\":\"decision\"")) decisionJson = events.next();
            }
            JsonNode decision = mapper.readTree(decisionJson);
            int answerId = decision.path("answerId").asInt();
            send(socket, mapper, Map.of("type", "refined", "sessionId", "report-session", "sequence", sequence++,
                    "answerId", answerId, "text", "Java является объектно-ориентированным языком. JVM исполняет байткод."));
            send(socket, mapper, Map.of("type", "refine_done", "sessionId", "report-session", "sequence", sequence++,
                    "answerId", answerId));
            send(socket, mapper, Map.of("type", "stop", "sessionId", "report-session", "sequence", sequence));

            String report = events.nextOfType("report");
            assertTrue(report.contains("\"excel\""), report);
            assertTrue(report.contains(".xlsx"), report);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        }
    }

    private WebSocket connect(Events events) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().newWebSocketBuilder()
                .header("X-Internal-Token", "test-internal-token-which-is-long-enough-32")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/interview-events"), events)
                .get(5, TimeUnit.SECONDS);
    }

    private static void send(WebSocket socket, ObjectMapper mapper, Map<String, ?> message) throws Exception {
        socket.sendText(mapper.writeValueAsString(message), true).get(5, TimeUnit.SECONDS);
    }

    private static final class Events implements WebSocket.Listener {
        private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder current = new StringBuilder();
        private volatile int closedCode = -1;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                messages.offer(current.toString());
                current.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closedCode = statusCode;
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }

        String next() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            if (message == null) throw new AssertionError("Сервер аналитики не прислал ожидаемое событие");
            return message;
        }

        boolean hasMessage() { return !messages.isEmpty(); }

        String nextOfType(String type) throws InterruptedException {
            String marker = "\"type\":\"" + type + "\"";
            while (true) {
                String message = next();
                if (message.contains(marker)) return message;
            }
        }
    }
}
