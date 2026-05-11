package com.exam.backend.service;

import com.exam.backend.dto.SaveResultRequest;
import com.exam.backend.model.ExamResult;
import com.exam.backend.model.UsedToken;
import com.exam.backend.repository.ExamResultRepository;
import com.exam.backend.repository.UsedTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResultService {

    private final ExamResultRepository resultRepository;
    private final UsedTokenRepository  usedTokenRepository;

    @Value("${storage.base-path:C:/exam-recordings}")
    private String basePath;

    public ExamResult saveResult(SaveResultRequest req) throws IOException {
        // ── One-time link check ──────────────────────────────────────────────────
        if (req.getJti() != null && !req.getJti().isBlank()) {
            if (usedTokenRepository.existsById(req.getJti())) {
                throw new IllegalStateException("This invite link has already been used.");
            }
        }

        String htmlPath = writeHtmlReport(req);

        ExamResult result = resultRepository.findBySessionKey(req.getSessionKey())
                .orElse(new ExamResult());

        result.setSessionKey(req.getSessionKey());
        result.setStudentName(req.getStudentName());
        result.setStudentEmail(req.getStudentEmail());
        result.setExamCode(req.getExamCode());
        result.setExamTitle(req.getExamTitle());
        result.setScore(req.getScore());
        result.setTotalMarks(req.getTotalMarks());
        result.setGrade(req.getGrade());
        result.setPdfPath(htmlPath);
        result.setStartedAt(parseStartedAt(req.getStartedAt()));

        ExamResult saved = resultRepository.save(result);

        // ── Mark token as used (after successful save) ───────────────────────────
        if (req.getJti() != null && !req.getJti().isBlank()) {
            usedTokenRepository.save(new UsedToken(
                req.getJti(),
                req.getExamCode(),
                req.getStudentEmail(),
                LocalDateTime.now()
            ));
        }

        return saved;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private LocalDateTime parseStartedAt(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes a detailed HTML result report to:
     *   {basePath}/{sessionKey}/{sessionKey}.html
     *
     * Each question block shows the full question text, all options with
     * colour-coded correct / user-selected highlights, and marks awarded.
     */
    private String writeHtmlReport(SaveResultRequest req) throws IOException {
        Path dir = Paths.get(basePath, req.getSessionKey());
        Files.createDirectories(dir);
        Path file = dir.resolve(req.getSessionKey() + ".html");

        double pct = (req.getTotalMarks() != null && req.getTotalMarks() > 0)
                ? (req.getScore() / req.getTotalMarks()) * 100 : 0;

        String gradeHtml = (req.getGrade() != null && !req.getGrade().isBlank())
                ? "<p><strong>Grade: " + esc(req.getGrade()) + "</strong></p>" : "";

        StringBuilder questions = new StringBuilder();
        List<Map<String, Object>> details = req.getDetails();
        if (details != null) {
            for (Map<String, Object> d : details) {
                questions.append(buildQuestionBlock(d));
            }
        }

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <title>Result – %s</title>
                  <style>
                    body { font-family: Arial, sans-serif; padding: 40px;
                           max-width: 860px; margin: 0 auto; color: #222; }
                    h1   { text-align: center; margin-bottom: 4px; }
                    .sub { text-align: center; color: #555; margin-bottom: 20px; }
                    .meta { display: flex; justify-content: space-between;
                            margin: 6px 0; font-size: 14px; }
                    .score-box { background: #f0f0f0; padding: 20px;
                                 border-radius: 8px; text-align: center;
                                 margin: 20px 0; }
                    .score-box h2 { margin: 0 0 8px; color: #2563eb; }
                    /* ── question blocks ── */
                    .q-block { border: 1px solid #ddd; border-radius: 8px;
                               padding: 16px; margin-bottom: 18px;
                               page-break-inside: avoid; }
                    .q-header { display: flex; justify-content: space-between;
                                align-items: center; margin-bottom: 10px; }
                    .q-num   { font-weight: bold; font-size: 15px; }
                    .q-marks { font-size: 14px; color: #555; }
                    .q-text  { font-size: 15px; margin: 6px 0 12px; }
                    /* ── options ── */
                    .option { padding: 8px 12px; margin-bottom: 6px;
                              border-radius: 4px; border: 1px solid #e0e0e0;
                              font-size: 14px; }
                    .opt-correct          { background: #e8f5e9; border-color: #4caf50;
                                            color: #2e7d32; }
                    .opt-correct-selected { background: #c8e6c9; border-color: #2e7d32;
                                            color: #1b5e20; font-weight: bold; }
                    .opt-wrong-selected   { background: #ffebee; border-color: #f44336;
                                            color: #c62828; }
                    /* ── subjective ── */
                    .subj { background: #f9f9f9; padding: 10px 14px;
                            border-radius: 4px; font-size: 14px; }
                    .subj div { margin-bottom: 5px; }
                    .correct-hint { color: #2e7d32; }
                    /* ── status badges ── */
                    .badge-correct   { color: green;  font-weight: bold; }
                    .badge-incorrect { color: red;    font-weight: bold; }
                    .badge-skipped   { color: #888;   font-weight: bold; }
                    @media print { body { padding: 20px; } }
                  </style>
                </head>
                <body>
                  <h1>%s</h1>
                  <p class="sub">Exam Code: %s</p>
                  <div class="meta"><strong>Student:</strong><span>%s</span></div>
                  <div class="meta"><strong>Date:</strong><span>%s</span></div>
                  <div class="score-box">
                    <h2>Score: %.2f / %.0f</h2>
                    <p>Percentage: %.2f%%</p>
                    %s
                  </div>
                  %s
                </body>
                </html>
                """.formatted(
                esc(req.getExamCode()),
                esc(req.getExamTitle()),
                esc(req.getExamCode()),
                esc(req.getStudentName()),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")),
                req.getScore(),
                req.getTotalMarks(),
                pct,
                gradeHtml,
                questions
        );

        Files.writeString(file, html, StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }

    @SuppressWarnings("unchecked")
    private String buildQuestionBlock(Map<String, Object> d) {
        boolean correct      = Boolean.TRUE.equals(d.get("correct"));
        boolean notAttempted = d.get("userAnswer") == null;
        Object  marksAwarded = d.get("marksAwarded");
        Object  totalMarks   = d.get("totalMarks");
        String  qNum         = String.valueOf(d.getOrDefault("questionNumber", "?"));
        String  qText        = esc(String.valueOf(d.getOrDefault("questionText", "")));
        String  qType        = String.valueOf(d.getOrDefault("questionType", "mcq"));

        String badge = correct
                ? "<span class='badge-correct'>&#10003; Correct</span>"
                : notAttempted
                    ? "<span class='badge-skipped'>&#8213; Not Attempted</span>"
                    : "<span class='badge-incorrect'>&#10007; Incorrect</span>";

        String marksLabel = formatMark(marksAwarded) + " / " + totalMarks;

        StringBuilder body = new StringBuilder();

        if ("subjective".equalsIgnoreCase(qType)) {
            // ── subjective ──────────────────────────────────────────────────
            String userAns = formatAnswer(d.get("userAnswer"));
            String corrAns = formatAnswer(d.get("correctAnswer"));
            body.append("<div class='subj'>")
                .append("<div><strong>Your answer:</strong> ").append(esc(userAns)).append("</div>")
                .append("<div class='correct-hint'><strong>Correct answer:</strong> ")
                .append(esc(corrAns)).append("</div>")
                .append("</div>");

        } else {
            // ── MCQ ─────────────────────────────────────────────────────────
            List<Object> options    = (List<Object>) d.get("options");
            List<String> correctIds = toStringList(d.get("correctAnswer"));
            List<String> userIds    = toStringList(d.get("userAnswer"));

            if (options != null) {
                for (Object optObj : options) {
                    Map<String, Object> opt = (Map<String, Object>) optObj;
                    String id      = String.valueOf(opt.getOrDefault("id", ""));
                    String type    = String.valueOf(opt.getOrDefault("type", "text"));
                    String text    = "image".equalsIgnoreCase(type)
                            ? "[Image option " + id.toUpperCase() + "]"
                            : esc(String.valueOf(opt.getOrDefault("text", "")));

                    boolean isCorrect  = correctIds.contains(id);
                    boolean isSelected = userIds.contains(id);

                    String cls;
                    String marker;
                    if (isCorrect && isSelected) { cls = "opt-correct-selected"; marker = "&#10003;"; }
                    else if (isCorrect)           { cls = "opt-correct";          marker = "&#10003;"; }
                    else if (isSelected)          { cls = "opt-wrong-selected";   marker = "&#10007;"; }
                    else                          { cls = "option";               marker = "&#9711;";  }

                    body.append("<div class='option ").append(cls).append("'>")
                        .append(marker).append(" (").append(id.toUpperCase()).append(") ")
                        .append(text)
                        .append("</div>");
                }
            }
        }

        return """
                <div class='q-block'>
                  <div class='q-header'>
                    <span class='q-num'>Question %s</span>
                    %s
                    <span class='q-marks'>%s</span>
                  </div>
                  <p class='q-text'>%s</p>
                  %s
                </div>
                """.formatted(qNum, badge, marksLabel, qText, body);
    }

    // ── tiny utilities ───────────────────────────────────────────────────────────

    /** HTML-escape a string to prevent XSS in the saved report. */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Format a marksAwarded value with a leading + for non-negative numbers. */
    private String formatMark(Object val) {
        if (val == null) return "0";
        try {
            double d = Double.parseDouble(val.toString());
            return (d >= 0 ? "+" : "") + String.format("%.2f", d);
        } catch (NumberFormatException e) {
            return val.toString();
        }
    }

    /** Convert a user/correct answer (may be String, List, or null) to a readable string. */
    @SuppressWarnings("unchecked")
    private String formatAnswer(Object val) {
        if (val == null) return "(Not answered)";
        if (val instanceof List<?> list) {
            return String.join(" / ", list.stream().map(Object::toString).toList());
        }
        return val.toString();
    }

    /** Return the value as a List<String>, handling String, List, or null. */
    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object val) {
        if (val == null) return List.of();
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(val.toString());
    }
}
