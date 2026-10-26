package ru.aireferent.diarization;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationBridgePeerTest {
    @Test
    void rejects_preview_results_for_audio_already_covered_by_final_decision() {
        assertTrue(IntegrationBridge.isPreviewAtOrBeforeDecision(142_500, 144_429));
        assertTrue(IntegrationBridge.isPreviewAtOrBeforeDecision(144_429, 144_429));
        assertFalse(IntegrationBridge.isPreviewAtOrBeforeDecision(144_430, 144_429));
        assertFalse(IntegrationBridge.isPreviewAtOrBeforeDecision(10, 0));
    }

    @Test
    void serializes_concurrent_audio_and_text_sends_on_one_websocket() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<WebSocket> socketRef = new AtomicReference<>();
        ScheduledExecutorService completions = Executors.newSingleThreadScheduledExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(40);
        try {
            WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                        if (method.getName().equals("sendText") || method.getName().equals("sendBinary")) {
                            int active = inFlight.incrementAndGet();
                            maxInFlight.accumulateAndGet(active, Math::max);
                            sends.incrementAndGet();
                            CompletableFuture<WebSocket> sent = new CompletableFuture<>();
                            if (active > 1) {
                                inFlight.decrementAndGet();
                                sent.completeExceptionally(new IllegalStateException("Send pending"));
                            } else {
                                completions.schedule(() -> {
                                    inFlight.decrementAndGet();
                                    sent.complete(socketRef.get());
                                    ready.countDown();
                                }, 3, TimeUnit.MILLISECONDS);
                            }
                            return sent;
                        }
                        return null;
                    });
            socketRef.set(socket);
            IntegrationBridge.Peer peer = new IntegrationBridge.Peer(ignored -> {});
            peer.attach(socket);

            for (int i = 0; i < 40; i++) {
                final int index = i;
                callers.submit(() -> {
                    if ((index & 1) == 0) peer.sendAudio(new byte[]{1, 2});
                    else peer.sendText("peek-" + index);
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "All queued WebSocket writes should complete");
            assertEquals(40, sends.get());
            assertEquals(1, maxInFlight.get(), "A WebSocket must have at most one pending send");
        } finally {
            callers.shutdownNow();
            completions.shutdownNow();
        }
    }
}
