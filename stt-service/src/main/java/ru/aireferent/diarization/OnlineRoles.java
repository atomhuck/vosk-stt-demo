package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Назначает роли по эталону, группам голосов и накопленным подтверждениям speaker ID.
 *
 * @version 1.0.0
 */
final class OnlineRoles {
    private static final double MIN_STABLE_EVIDENCE_SECONDS = 2.5;
    private static final double MIN_NEW_GROUP_SECONDS = 2.5;
    /** Прототипы голоса одного интервьюера.
     *
     * @version 1.0.0
     */
    private static final class Interviewer {
        final int number;
        final List<float[]> embeddings = new ArrayList<>();
        Interviewer(int number, float[] initial) { this.number = number; embeddings.add(initial); }
        double similarity(float[] embedding) { return Support.dot(Support.meanPrototype(embeddings), embedding); }
    }

    private final float[] candidate;
    private final SpeakerEmbeddingExtractor extractor;
    private final double threshold;
    private final List<Interviewer> interviewers = new ArrayList<>();
    private final Map<String, Map<String, Double>> sourceVotes = new LinkedHashMap<>();

    OnlineRoles(float[] candidate, SpeakerEmbeddingExtractor extractor, double threshold) {
        this.candidate = candidate;
        this.extractor = extractor;
        this.threshold = threshold;
    }

    private String stableRole(String source) {
        Map<String, Double> votes = sourceVotes.get(source);
        if (votes == null) return null;
        String best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (var entry : votes.entrySet()) {
            if (entry.getValue() > score) { score = entry.getValue(); best = entry.getKey(); }
        }
        return score >= MIN_STABLE_EVIDENCE_SECONDS ? best : null;
    }

    private void remember(String source, String label, double duration) {
        sourceVotes.computeIfAbsent(source, ignored -> new LinkedHashMap<>())
                .merge(label, duration, Double::sum);
    }

    Map<String, Object> preview(float[] audio, double start, double end, String source) {
        String label = stableRole(source);
        String basis;
        Double candidateScore = null, bestScore = null;
        if (label != null) basis = "speaker ID в текущей потоковой сессии";
        else {
            float[] embedding = Support.embedding(extractor, audio, Math.max(start, end - 2.0), end, 0);
            if (embedding == null) {
                label = "Не определено";
                basis = "ожидание достаточного фрагмента";
            } else {
                candidateScore = Support.dot(candidate, embedding);
                int bestIndex = -1;
                double maxInterviewerScore = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < interviewers.size(); i++) {
                    double score = interviewers.get(i).similarity(embedding);
                    if (score > maxInterviewerScore) {
                        bestIndex = i;
                        maxInterviewerScore = score;
                    }
                }
                if (bestIndex >= 0) bestScore = maxInterviewerScore;
                if (candidateScore >= threshold && (bestScore == null || candidateScore - bestScore >= .05)) {
                    label = "Кандидат";
                    basis = "предварительное сравнение голосов";
                } else if (bestIndex >= 0 && bestScore != null && bestScore >= threshold
                        && bestScore - candidateScore >= .05) {
                    label = "Интервьюер " + interviewers.get(bestIndex).number;
                    basis = "предварительное сравнение голосов";
                } else {
                    label = "Не определено";
                    basis = "сходство ниже порога";
                }
            }
        }
        return Support.map("start", Support.round(start, 3), "observed_end", Support.round(end, 3),
                "source_speaker_id", source, "label", label, "status", "предварительно", "basis", basis,
                "candidate_similarity", candidateScore == null ? null : Support.round(candidateScore, 4),
                "interviewer_similarity", bestScore == null ? null : Support.round(bestScore, 4));
    }

    Map<String, Object> classify(float[] audio, double start, double end, String source) {
        float[] embedding = Support.embedding(extractor, audio, start, end, .08);
        Double candidateScore = embedding == null ? null : Support.dot(candidate, embedding);
        int best = -1;
        Double bestScore = null;
        if (embedding != null) for (int i = 0; i < interviewers.size(); i++) {
            double score = interviewers.get(i).similarity(embedding);
            if (bestScore == null || score > bestScore) { best = i; bestScore = score; }
        }
        String stable = stableRole(source);
        double duration = end - start;
        String label, decision, confidence;
        boolean candidateMatch = candidateScore != null && candidateScore >= threshold
                && (bestScore == null || candidateScore - bestScore >= .05);
        boolean interviewerMatch = bestScore != null && bestScore >= threshold
                && (candidateScore == null || bestScore - candidateScore >= .05);

        if (candidateMatch) {
            remember(source, "Кандидат", duration);
            boolean confirmed = duration >= MIN_STABLE_EVIDENCE_SECONDS || "Кандидат".equals(stableRole(source));
            label = confirmed ? "Кандидат" : "Не определено";
            decision = confirmed ? "Сходство с эталоном подтверждено порогом и достаточной речью"
                    : "Сходство с эталоном предварительное; накапливаем 2,5 с чистой речи speaker ID";
            confidence = confirmed ? (duration >= MIN_STABLE_EVIDENCE_SECONDS ? "высокая" : "средняя") : "низкая";
        } else if (interviewerMatch) {
            Interviewer group = interviewers.get(best);
            String groupLabel = "Интервьюер " + group.number;
            remember(source, groupLabel, duration);
            boolean confirmed = duration >= MIN_STABLE_EVIDENCE_SECONDS || groupLabel.equals(stableRole(source));
            label = confirmed ? groupLabel : "Не определено";
            decision = confirmed ? "Совпадение с группой интервьюера подтверждено достаточной речью"
                    : "Сходство предварительное; накапливаем 2,5 с чистой речи speaker ID";
            confidence = confirmed ? (duration >= MIN_STABLE_EVIDENCE_SECONDS ? "высокая" : "средняя") : "низкая";
            if (duration >= 1.5) group.embeddings.add(embedding);
        } else if (stable != null) {
            // Ранее подтверждённый speaker ID помогает короткой реплике,
            // но не считается новым прямым голосовым совпадением.
            label = stable;
            decision = "speaker ID ранее подтверждён голосовым совпадением";
            confidence = "низкая";
        } else if (embedding != null && candidateScore != null && candidateScore < threshold
                && (bestScore == null || bestScore < threshold) && duration >= MIN_NEW_GROUP_SECONDS) {
            Interviewer group = new Interviewer(interviewers.size() + 1, embedding);
            interviewers.add(group);
            label = "Интервьюер " + group.number;
            decision = "Создана группа другого голоса";
            confidence = "средняя";
            remember(source, label, duration);
        } else {
            label = "Не определено";
            decision = embedding == null ? "Недостаточно речи для эмбеддинга; ожидаем подтверждения роли"
                    : "Сходство ниже порога; ожидаем более длинную реплику";
            confidence = "низкая";
        }
        int confidencePercent = ConfidenceScore.percent(confidence, label, decision,
                candidateScore, bestScore, threshold);
        confidence = ConfidenceScore.label(confidencePercent);
        return Support.map("start", Support.round(start, 3), "end", Support.round(end, 3),
                "duration", Support.round(duration, 3), "label", label,
                "candidate_similarity", candidateScore == null ? null : Support.round(candidateScore, 4),
                "interviewer_similarity", bestScore == null ? null : Support.round(bestScore, 4),
                "source_speaker_id", source, "confidence", confidence,
                "confidence_percent", confidencePercent, "decision", decision);
    }
}
