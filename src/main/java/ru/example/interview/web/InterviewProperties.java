package ru.example.interview.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * interview.questions-path / glossary-path — внешние файлы вместо встроенных (необязательно).
 * interview.reports-dir — куда сохранять Excel-отчёты; interview.report-download — давать ли скачать их со страницы.
 * interview.next-extra-ms / other-next-extra-ms / follow-up-extra-ms — доп. пауза до решения (см. LiveInterview); grace-ms — окно отмены.
 * interview.end-silence-ms — сколько тишины считать концом ответа (главный рычаг задержки решения).
 */
@ConfigurationProperties(prefix = "interview")
public record InterviewProperties(String questionsPath, String glossaryPath, String spokenPath, Integer maxFollowUps,
                                  Integer endSilenceMs, Integer noSpeechTimeoutMs, Double weakCoverage,
                                  Integer refinementWaitMs, String reportsDir, Boolean reportDownload,
                                  Integer nextExtraMs, Integer otherNextExtraMs, Integer followUpExtraMs, Integer graceMs) {
    public InterviewProperties {
        if (maxFollowUps == null) maxFollowUps = 2;
        if (endSilenceMs == null) endSilenceMs = 450;
        if (noSpeechTimeoutMs == null) noSpeechTimeoutMs = 15000;
        if (weakCoverage == null) weakCoverage = 0.08;
        if (refinementWaitMs == null) refinementWaitMs = 25000;
        if (reportsDir == null || reportsDir.isBlank()) reportsDir = "./reports";
        if (reportDownload == null) reportDownload = true;
        if (nextExtraMs == null) nextExtraMs = 150;
        if (otherNextExtraMs == null) otherNextExtraMs = 600;
        if (followUpExtraMs == null) followUpExtraMs = 1500;
        if (graceMs == null) graceMs = 1000;
    }
}
