package ru.aireferent.diarization;

/**
 * Вычисляет объяснимую оценку роли для интерфейса, а не вероятность правильности.
 *
 * @version 1.0.0
 */
final class ConfidenceScore {
    private ConfidenceScore() {}

    static int percent(String tier, String label, String decision, Double candidateSimilarity,
                       Double interviewerSimilarity, double threshold) {
        if ("эталон".equals(tier)) return 100;
        if ("Не определено".equals(label)) return 0;
        double boundary = Math.max(.000001, Math.min(.999999, threshold));
        Double selected = "Кандидат".equals(label) ? candidateSimilarity : interviewerSimilarity;
        if ("низкая".equals(tier)) {
            if ("Создана группа другого голоса".equals(decision) && candidateSimilarity != null)
                return (int) Math.round(49 * (1 - clamp(candidateSimilarity / boundary)));
            if (selected == null) return 0;
            return (int) Math.round(49 * clamp(selected / boundary));
        }
        double strength;
        if ("Создана группа другого голоса".equals(decision)) {
            strength = candidateSimilarity == null ? 0 : 1 - clamp(candidateSimilarity / boundary);
        } else if (selected == null) {
            strength = 0;
        } else {
            double aboveThreshold = clamp((selected - boundary) / (1 - boundary));
            Double rival = "Кандидат".equals(label) ? interviewerSimilarity : candidateSimilarity;
            double separation = rival == null ? 0 : clamp((selected - rival) / (1 - boundary));
            strength = .75 * aboveThreshold + .25 * separation;
        }
        return ("высокая".equals(tier) ? 75 : 50) + (int) Math.round(24 * strength);
    }

    static String label(int percent) {
        if (percent == 100) return "эталон";
        return percent >= 75 ? "высокая" : percent >= 50 ? "средняя" : "низкая";
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
