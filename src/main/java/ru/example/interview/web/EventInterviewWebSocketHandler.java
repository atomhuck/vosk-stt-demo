package ru.example.interview.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import ru.example.interview.engine.DecisionPolicy;
import ru.example.interview.engine.EndpointDetector;
import ru.example.interview.engine.InterviewKnowledge;
import ru.example.interview.engine.InterviewSession;
import ru.example.interview.engine.Level;
import ru.example.interview.live.EventSpeechEngine;
import ru.example.interview.live.LiveInterview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Внутренний канал событий STT → аналитика; сырой PCM здесь не принимается. */
@Component
public final class EventInterviewWebSocketHandler extends AbstractWebSocketHandler {
    private static final class Active {
        final LiveInterview live;
        final EventSpeechEngine speech;
        final String sessionId;
        final AtomicLong outboundSequence;
        final AtomicLong audioSample;
        long nextSequence = 1;

        Active(LiveInterview live, EventSpeechEngine speech, String sessionId,
               AtomicLong outboundSequence, AtomicLong audioSample) {
            this.live = live;
            this.speech = speech;
            this.sessionId = sessionId;
            this.outboundSequence = outboundSequence;
            this.audioSample = audioSample;
        }
    }

    private final InterviewKnowledge knowledge;
    private final DecisionPolicy policy;
    private final InterviewProperties properties;
    private final ExcelReportService reports;
    private final ObjectMapper mapper;
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public EventInterviewWebSocketHandler(InterviewKnowledge knowledge, DecisionPolicy policy,
                                          InterviewProperties properties, ExcelReportService reports,
                                          ObjectMapper mapper) {
        this.knowledge = knowledge;
        this.policy = policy;
        this.properties = properties;
        this.reports = reports;
        this.mapper = mapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws IOException {
        JsonNode data = mapper.readTree(message.getPayload());
        String type = data.path("type").asText();
        try {
            if ("start".equals(type)) {
                start(ws, data);
                return;
            }
            Active session = active.get(ws.getId());
            if (session == null) throw new IllegalStateException("Сначала отправьте start");
            if (!session.sessionId.equals(data.path("sessionId").asText()))
                throw new IllegalArgumentException("Неверный sessionId");
            long sequence = data.path("sequence").asLong(-1);
            if (sequence < session.nextSequence) return;
            if (sequence != session.nextSequence)
                throw new IllegalStateException("Пропущено событие STT: ожидалось " + session.nextSequence);
            session.nextSequence++;
            long audioEnd = data.path("audioEndSample").asLong(-1);
            long audioStart = data.path("audioStartSample").asLong(-1);
            if (audioEnd >= 0) session.audioSample.accumulateAndGet(audioEnd, Math::max);
            else if (audioStart >= 0) session.audioSample.accumulateAndGet(audioStart, Math::max);
            switch (type) {
                case "ready" -> session.live.onReady();
                case "tts_end" -> session.live.onTtsEnd(data.path("afterMs").asLong(0));
                case "skip" -> session.live.onSkip();
                case "stop" -> session.live.onStop();
                case "peek" -> session.live.onPeek(data.path("text").asText(""), data.path("requestId").asText(""));
                case "frame" -> session.live.onSttFrame(data.path("asrType").asText(""),
                        data.path("text").asText(""), data.path("samples").asInt(0),
                        data.path("rms").asDouble(0), data.path("asrMs").asDouble(0));
                case "refined" -> session.live.onRefined(data.path("answerId").asInt(-1), data.path("text").asText(""));
                case "refine_done" -> session.live.onRefineDone(data.path("answerId").asInt(-1));
                default -> throw new IllegalArgumentException("Неизвестное событие: " + type);
            }
        } catch (RuntimeException error) {
            Map<String, Object> failure = Map.of("type", "error", "message", String.valueOf(error.getMessage()));
            Active session = active.get(ws.getId());
            if (session == null) send(ws, failure);
            else sendSession(ws, session.sessionId, session.outboundSequence, session.audioSample, failure);
            ws.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    private void start(WebSocketSession ws, JsonNode data) throws IOException {
        if (active.containsKey(ws.getId())) throw new IllegalStateException("Интервью уже началось");
        String sessionId = data.path("sessionId").asText("");
        if (sessionId.isBlank()) throw new IllegalArgumentException("Отсутствует sessionId");
        List<Level> levels = new ArrayList<>();
        for (JsonNode level : data.path("levels")) levels.add(Level.parse(level.asText()));
        EndpointDetector.Config config = new EndpointDetector.Config();
        config.baseSilenceMs = properties.endSilenceMs();
        config.noSpeechTimeoutMs = properties.noSpeechTimeoutMs();
        EventSpeechEngine speech = new EventSpeechEngine();
        AtomicLong outboundSequence = new AtomicLong(1);
        AtomicLong audioSample = new AtomicLong();
        LiveInterview live = new LiveInterview(speech, new InterviewSession(knowledge, levels, policy),
                new EndpointDetector(config), event -> sendSession(ws, sessionId, outboundSequence, audioSample, event));
        live.setTiming(new LiveInterview.Timing(properties.nextExtraMs(), properties.otherNextExtraMs(),
                properties.followUpExtraMs(), properties.graceMs()));
        live.setRefinementWait(properties.refinementWaitMs());
        live.setExport(reports, data.path("candidate").asText(""), Map.of("Распознавание речи", "STT-сервис"));
        active.put(ws.getId(), new Active(live, speech, sessionId, outboundSequence, audioSample));
        live.start();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Active session = active.remove(ws.getId());
        if (session != null) session.speech.close();
    }

    private void send(WebSocketSession ws, Map<String, ?> event) {
        try {
            String json = mapper.writeValueAsString(event);
            synchronized (ws) {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(json));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void sendSession(WebSocketSession ws, String sessionId, AtomicLong sequence,
                             AtomicLong audioSample, Map<String, ?> payload) {
        synchronized (ws) {
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            payload.forEach(event::put);
            event.put("sessionId", sessionId);
            event.put("sequence", sequence.getAndIncrement());
            event.put("audioSample", audioSample.get());
            send(ws, event);
        }
    }
}
