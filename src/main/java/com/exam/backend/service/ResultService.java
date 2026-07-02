package com.exam.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.exam.backend.dto.SaveResultRequest;
import com.exam.backend.dto.ScoredResult;
import com.exam.backend.model.AiResult;
import com.exam.backend.model.AiResultType;
import com.exam.backend.model.ExamResult;
import com.exam.backend.model.UsedToken;
import com.exam.backend.repository.AiResultRepository;
import com.exam.backend.repository.ExamRepository;
import com.exam.backend.repository.ExamResultRepository;
import com.exam.backend.repository.UsedTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResultService {

    private final ExamResultRepository    resultRepository;
    private final AiResultRepository      aiResultRepository;
    private final UsedTokenRepository     usedTokenRepository;
    private final ExamRepository          examRepository;
    private final ObjectMapper            mapper;
    private final VerbalEvaluationService verbalEvaluationService;
    private final ChatHistoryRegistry chatHistoryRegistry;

    @Value("${storage.base-path:C:/exam-recordings}")
    private String basePath;

    // ── Public API ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public ScoredResult saveResult(SaveResultRequest req) throws IOException {
    	log.info("Initiating save result for sessionKey: {}, examCode: {}", req.getSessionKey(), req.getExamCode());

        // ── One-time link check ──────────────────────────────────────────────────
        // Allow the save if the sessionKey already exists — this handles the
        // beacon-then-normal-submit race (or vice-versa).  Only reject when a
        // *different* session tries to reuse the same invite token.
        if (req.getJti() != null && !req.getJti().isBlank()) {
        	log.debug("Checking token usage for JTI: {}", req.getJti());
            if (usedTokenRepository.existsById(req.getJti())) {
                boolean sameSession = resultRepository.findBySessionKey(req.getSessionKey()).isPresent();
                if (!sameSession) {
                	log.warn("Save rejected: Invite link with JTI {} has already been used by a different session", req.getJti());
                    throw new IllegalStateException("This invite link has already been used.");
                }
            }
        }

        // ── Load full exam from DB (correct answers never leave the server) ───────
        log.debug("Loading full exam data from DB for examCode: {}", req.getExamCode());
        String examJson = examRepository.findByExamCodeIgnoreCase(req.getExamCode())
                .map(com.exam.backend.model.Exam::getExamData)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Exam not found: " + req.getExamCode()));

        Map<String, Object> examMap = mapper.readValue(examJson, Map.class);

        // ── Score answers server-side ─────────────────────────────────────────────
        log.debug("Scoring student answers server-side");
        List<Map<String, Object>> details = scoreAnswers(
                examMap, req.getAnswers(), req.getQuestionOrderMap());

        // Verbal questions are scored asynchronously — exclude from MCQ totals
        double totalMarks = 0;
        double rawScore   = 0;
        for (Map<String, Object> d : details) {
            String qt = String.valueOf(d.get("questionType"));
            if (!"verbal".equalsIgnoreCase(qt) && !"subjective".equalsIgnoreCase(qt)) {
                totalMarks += ((Number) d.get("totalMarks")).doubleValue();
                rawScore   += ((Number) d.get("marksAwarded")).doubleValue();
            }
        }
        double score = Math.max(0, rawScore);
        String grade = resolveGrade(examMap, score, totalMarks);
        log.info("Scoring complete. Score: {}/{}, Grade: {}", score, totalMarks, grade);

        // ── Derive exam title ─────────────────────────────────────────────────────
        String examTitle = (req.getExamTitle() != null && !req.getExamTitle().isBlank())
                ? req.getExamTitle()
                : String.valueOf(examMap.getOrDefault("examTitle", req.getExamCode()));

        // ── Write HTML report ─────────────────────────────────────────────────────
        log.debug("Generating HTML report for sessionKey: {}", req.getSessionKey());
        String htmlPath = writeHtmlReport(
                req.getSessionKey(), req.getStudentName(),
                req.getExamCode(), examTitle,
                score, totalMarks, grade, details);

        // ── Persist result ────────────────────────────────────────────────────────
        ExamResult result = resultRepository.findBySessionKey(req.getSessionKey())
                .orElse(new ExamResult());

        result.setSessionKey(req.getSessionKey());
        result.setStudentName(req.getStudentName());
        result.setStudentEmail(req.getStudentEmail());
        result.setExamCode(req.getExamCode());
        result.setExamTitle(examTitle);
        result.setScore(score);
        result.setTotalMarks(totalMarks);
        result.setGrade(grade);
        result.setPdfPath(htmlPath);
        result.setStartedAt(parseStartedAt(req.getStartedAt()));
        if (req.getJti() != null && !req.getJti().isBlank()) {
            result.setJti(req.getJti());
        }
        if (req.getViolations() != null) {
            result.setViolations(req.getViolations());
        }
        // Persist per-question MCQ breakdown so admin report can display it
        try {
            result.setAnswersJson(mapper.writeValueAsString(details));
        } catch (Exception ignored) { /* non-critical — report falls back gracefully */ }
        // totalScore is not stored on exam_results — the admin portal computes it
        // on the fly from the ai_result table, so there is nothing to set here.

        ExamResult saved = resultRepository.save(result);
        log.info("Successfully persisted ExamResult with ID: {}", saved.getId());
        chatHistoryRegistry.clear(req.getSessionKey());
        
        List<AiResult> aiResultsToFire = new java.util.ArrayList<>();
//      Gets IDs of existing rows (verbal uploaded earlier)
//      Loops through all questions
//      Creates rows only for questions that don't already have one
//      Subjective rows now get created even if verbal rows already exist
     // Collect questionIds that already have AiResult rows
     List<String> existingQuestionIds = aiResultRepository.findByExamResult(saved)
             .stream()
             .map(AiResult::getQuestionId)
             .toList();
     // Always loop — only create rows that don't already exist
     for (Map<String, Object> d : details) {
         String quesType = String.valueOf(d.get("questionType"));
         if (!"verbal".equalsIgnoreCase(quesType) && !"subjective".equalsIgnoreCase(quesType)) continue;

         String questionId = String.valueOf(d.get("questionId"));
         if (existingQuestionIds.contains(questionId)) continue; // skip if already created at audio upload

         AiResult ar = new AiResult();
         ar.setExamResult(saved);
         ar.setJti(saved.getJti());
         ar.setQuestionId(questionId);
         ar.setQuestion(String.valueOf(d.get("questionText")));
         ar.setMaxMarks(((Number) d.get("totalMarks")).doubleValue());
         ar.setExpectedReply(String.valueOf(d.getOrDefault("expectedReply", "")));
         ar.setPrecisionLevel(((Number) d.getOrDefault("precisionLevel", 3)).intValue());
         if ("verbal".equalsIgnoreCase(quesType)) {
             ar.setInputText(saved.getSessionKey() + "/verbal_" + questionId + ".webm");
             ar.setType(AiResultType.VERBAL);
         } else {
             Object userAns = d.get("userAnswer");
             ar.setInputText(userAns != null ? userAns.toString() : "");
             ar.setType(AiResultType.SUBJECTIVE);
         }
         ar.setStatus("PENDING");
         aiResultsToFire.add(aiResultRepository.save(ar));
     }
        verbalEvaluationService.fireVerbalEvaluations(aiResultsToFire);

        // ── Mark token as used (idempotent) ──────────────────────────────────────
        if (req.getJti() != null && !req.getJti().isBlank()
                && !usedTokenRepository.existsById(req.getJti())) {
            usedTokenRepository.save(new UsedToken(
                    req.getJti(), req.getExamCode(), req.getStudentEmail(), LocalDateTime.now()));
            log.info("Marked JTI {} as used", req.getJti());
        }

        return new ScoredResult(saved, score, totalMarks, grade, details);
    }

    // ── Server-side scoring ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scoreAnswers(
            Map<String, Object> examMap,
            List<Map<String, Object>> rawAnswers,
            Map<String, Object> questionOrderMap) {
    	log.debug("Processing {} raw answers", rawAnswers != null ? rawAnswers.size() : 0);

        // Build lookup: questionId → raw answer value (String or List)
        Map<String, Object> answerLookup = new HashMap<>();
        if (rawAnswers != null) {
            for (Map<String, Object> entry : rawAnswers) {
                String qId = String.valueOf(entry.get("questionId"));
                answerLookup.put(qId, entry.get("answer"));
            }
        }

        List<Map<String, Object>> details = new ArrayList<>();
        List<Object> sections = (List<Object>) examMap.get("sections");
        if (sections == null) {
        	log.warn("Exam JSON contains no sections");
        	return details;
        }

        for (Object sectionObj : sections) {
            Map<String, Object> section = (Map<String, Object>) sectionObj;
            List<Object> questions = (List<Object>) section.get("questions");
            if (questions == null) continue;

            for (Object qObj : questions) {
                Map<String, Object> q = (Map<String, Object>) qObj;

                String  qId      = String.valueOf(q.get("id"));
                String  qText    = String.valueOf(q.getOrDefault("question", ""));
                String  qType    = String.valueOf(q.getOrDefault("type", "mcq"));
                double  marks    = ((Number) q.getOrDefault("marks", 0)).doubleValue();
                double  negMarks = ((Number) q.getOrDefault("negativeMarks", 0)).doubleValue();
                boolean multi    = Boolean.TRUE.equals(q.get("multipleChoice"));

                // Resolve display number from the order map or fall back to JSON number
                int displayNumber = 0;
                if (questionOrderMap != null && questionOrderMap.containsKey(qId)) {
                    displayNumber = ((Number) questionOrderMap.get(qId)).intValue();
                } else if (q.get("number") != null) {
                    displayNumber = ((Number) q.get("number")).intValue();
                }

                // ── Verbal questions are scored by AI asynchronously — skip MCQ logic ──
                if ("verbal".equalsIgnoreCase(qType)) {
                    Map<String, Object> verbalDetail = new LinkedHashMap<>();
                    verbalDetail.put("questionId",     qId);
                    verbalDetail.put("questionNumber", displayNumber);
                    verbalDetail.put("questionText",   qText);
                    verbalDetail.put("questionType",   qType);
                    verbalDetail.put("options",        null);
                    verbalDetail.put("correctAnswer",  List.of());
                    verbalDetail.put("userAnswer",     answerLookup.containsKey(qId) ? "recorded" : null);
                    verbalDetail.put("correct",        false);
                    verbalDetail.put("marksAwarded",   0.0);
                    verbalDetail.put("totalMarks",     marks);
                    // Stored in AiResult row for sending to the AI API
                    verbalDetail.put("expectedReply",  q.getOrDefault("expectedReply", ""));
                    verbalDetail.put("precisionLevel", ((Number) q.getOrDefault("precision", 3)).intValue());
                    details.add(verbalDetail);
                    continue; // do NOT accumulate into totalMarks / rawScore
                }
                
                if ("subjective".equalsIgnoreCase(qType)) {
                    Map<String, Object> subjDetail = new LinkedHashMap<>();
                    subjDetail.put("questionId",     qId);
                    subjDetail.put("questionNumber", displayNumber);
                    subjDetail.put("questionText",   qText);
                    subjDetail.put("questionType",   qType);
                    subjDetail.put("options",        null);
                    subjDetail.put("correctAnswer",  List.of());
                    Object userAns = answerLookup.get(qId);
                    subjDetail.put("userAnswer",     userAns != null ? userAns.toString() : null);
                    subjDetail.put("correct",        false);
                    subjDetail.put("marksAwarded",   0.0);
                    subjDetail.put("totalMarks",     marks);
                    subjDetail.put("expectedReply",  q.getOrDefault("expectedReply", ""));
                    subjDetail.put("precisionLevel", ((Number) q.getOrDefault("precision", 3)).intValue());
                    details.add(subjDetail);
                    continue; // do NOT accumulate into totalMarks / rawScore
                }
                
                List<Object> correctRaw = (List<Object>) q.get("correctAnswer");
                List<String> correctAnswer = correctRaw == null ? List.of()
                        : correctRaw.stream().map(Object::toString).toList();

                List<Object> options = (List<Object>) q.get("options");

                List<String> userAnswer = toStringList(answerLookup.get(qId));
                boolean notAttempted = userAnswer.isEmpty();

                boolean correct       = false;
                double  marksAwarded  = 0;
                if (!notAttempted) {
                    correct      = checkAnswer(qType, multi, correctAnswer, userAnswer);
                    marksAwarded = correct ? marks : -negMarks;
                }

                // Normalise userAnswer shape to match what the HTML report + PDF expect
                Object userAnswerOut;
                if (notAttempted) {
                    userAnswerOut = null;
                } else if (multi || "subjective".equalsIgnoreCase(qType)) {
                    userAnswerOut = userAnswer;
                } else {
                    userAnswerOut = userAnswer.get(0);
                }

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("questionId",     qId);
                detail.put("questionNumber", displayNumber);
                detail.put("questionText",   qText);
                detail.put("questionType",   qType);
                detail.put("options",        options);
                detail.put("correctAnswer",  correctAnswer);
                detail.put("userAnswer",     userAnswerOut);
                detail.put("correct",        correct);
                detail.put("marksAwarded",   marksAwarded);
                detail.put("totalMarks",     marks);
                details.add(detail);
            }
        }

        details.sort(Comparator.comparingInt(d -> ((Number) d.get("questionNumber")).intValue()));
        return details;
    }

    private boolean checkAnswer(String qType, boolean multi,
                                List<String> correct, List<String> user) {
        if ("subjective".equalsIgnoreCase(qType)) {
            String ua = user.isEmpty() ? "" : user.get(0).toLowerCase().trim();
            return correct.stream().anyMatch(ca -> ca.toLowerCase().trim().equals(ua));
        } else if (multi) {
            return user.stream().sorted().toList()
                    .equals(correct.stream().sorted().toList());
        } else {
            String ua = user.isEmpty() ? "" : user.get(0);
            return !correct.isEmpty() && correct.get(0).equals(ua);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveGrade(Map<String, Object> examMap, double score, double totalMarks) {
        List<Object> grading = (List<Object>) examMap.get("grading");
        if (grading == null || grading.isEmpty()) return null;
        double pct = totalMarks > 0 ? (score / totalMarks) * 100 : 0;
        return grading.stream()
                .map(g -> (Map<String, Object>) g)
                .sorted(Comparator.comparingDouble(
                        g -> -((Number) g.get("minPercentage")).doubleValue()))
                .filter(g -> pct >= ((Number) g.get("minPercentage")).doubleValue())
                .map(g -> String.valueOf(g.get("grade")))
                .findFirst()
                .orElse("F");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private LocalDateTime parseStartedAt(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
        	log.warn("Failed to parse startedAt ISO string: {}", iso, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object val) {
        if (val == null) return List.of();
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        String s = val.toString().trim();
        return s.isBlank() ? List.of() : List.of(s);
    }

    // ── HTML report ───────────────────────────────────────────────────────────────

    private String writeHtmlReport(
            String sessionKey, String studentName,
            String examCode, String examTitle,
            double score, double totalMarks, String grade,
            List<Map<String, Object>> details) throws IOException {

        Path dir  = Paths.get(basePath, sessionKey);
        Files.createDirectories(dir);
        Path file = dir.resolve(sessionKey + ".html");

        double pct       = totalMarks > 0 ? (score / totalMarks) * 100 : 0;
        String gradeHtml = (grade != null && !grade.isBlank())
                ? "<p><strong>Grade: " + esc(grade) + "</strong></p>" : "";

        StringBuilder questions = new StringBuilder();
        for (Map<String, Object> d : details) {
            questions.append(buildQuestionBlock(d));
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
                    .q-block { border: 1px solid #ddd; border-radius: 8px;
                               padding: 16px; margin-bottom: 18px;
                               page-break-inside: avoid; }
                    .q-header { display: flex; justify-content: space-between;
                                align-items: center; margin-bottom: 10px; }
                    .q-num   { font-weight: bold; font-size: 15px; }
                    .q-marks { font-size: 14px; color: #555; }
                    .q-text  { font-size: 15px; margin: 6px 0 12px; }
                    .option { padding: 8px 12px; margin-bottom: 6px;
                              border-radius: 4px; border: 1px solid #e0e0e0;
                              font-size: 14px; }
                    .opt-correct          { background: #e8f5e9; border-color: #4caf50; color: #2e7d32; }
                    .opt-correct-selected { background: #c8e6c9; border-color: #2e7d32; color: #1b5e20; font-weight: bold; }
                    .opt-wrong-selected   { background: #ffebee; border-color: #f44336; color: #c62828; }
                    .subj { background: #f9f9f9; padding: 10px 14px; border-radius: 4px; font-size: 14px; }
                    .subj div { margin-bottom: 5px; }
                    .correct-hint { color: #2e7d32; }
                    .badge-correct   { color: green; font-weight: bold; }
                    .badge-incorrect { color: red;   font-weight: bold; }
                    .badge-skipped   { color: #888;  font-weight: bold; }
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
                esc(examCode),
                esc(examTitle),
                esc(examCode),
                esc(studentName),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")),
                score, totalMarks, pct,
                gradeHtml,
                questions);

        Files.writeString(file, html, StandardCharsets.UTF_8);
        log.info("Successfully saved HTML report to: {}", file.toAbsolutePath());
        return file.toAbsolutePath().toString();
    }
    @SuppressWarnings("unchecked")
    public void regenerateHtmlReport(ExamResult result, List<AiResult> aiResults) {
        if (result.getPdfPath() == null || result.getPdfPath().isBlank()) {
            log.warn("regenerateHtmlReport: no pdfPath on result id={} — skipping", result.getId());
            return;
        }
        try {
            // ── Rebuild detail list from stored answers JSON ──────────────────────
            List<Map<String, Object>> details;
            if (result.getAnswersJson() != null && !result.getAnswersJson().isBlank()) {
                details = mapper.readValue(result.getAnswersJson(),
                        mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            } else {
                log.warn("regenerateHtmlReport: no answersJson on result id={} — skipping", result.getId());
                return;
            }

            // ── Build lookup: questionId → AiResult ──────────────────────────────
            Map<String, AiResult> aiMap = new LinkedHashMap<>();
            for (AiResult ar : aiResults) {
                if (ar.getQuestionId() != null) aiMap.put(ar.getQuestionId(), ar);
            }

            // ── Overlay AI scores onto verbal / subjective detail rows ────────────
            double verbalScore    = 0.0;
            double verbalMaxMarks = 0.0;
            for (Map<String, Object> d : details) {
                String qType = String.valueOf(d.getOrDefault("questionType", "mcq"));
                if (!"verbal".equalsIgnoreCase(qType) && !"subjective".equalsIgnoreCase(qType)) continue;

                String qId  = String.valueOf(d.get("questionId"));
                AiResult ar = aiMap.get(qId);
                if (ar == null) continue;

                double maxMarks = ar.getMaxMarks() != null ? ar.getMaxMarks() : 0.0;
                verbalMaxMarks += maxMarks;

                if ("SUCCESS".equals(ar.getStatus()) && ar.getAiScore() != null) {
                    double aiScore = ar.getAiScore();
                    verbalScore += aiScore;
                    d.put("marksAwarded", aiScore);
                    d.put("aiScore",      aiScore);
                    d.put("aiStatus",     "SUCCESS");
                    if (ar.getTranscript() != null) d.put("transcript", ar.getTranscript());
                    if (ar.getFeedback()   != null) d.put("feedback",   ar.getFeedback());
                } else {
                    d.put("aiStatus", ar.getStatus() != null ? ar.getStatus() : "PENDING");
                }
            }

            // ── Recompute total score & grade ─────────────────────────────────────
            double mcqScore    = result.getScore()      != null ? result.getScore()      : 0.0;
            double mcqMax      = result.getTotalMarks()  != null ? result.getTotalMarks() : 0.0;
            double totalScore    = Math.round((mcqScore + verbalScore) * 100.0) / 100.0;
            double totalMaxMarks = mcqMax + verbalMaxMarks;

            // Re-resolve grade using the exam's grading rules (loaded from DB)
            String grade = result.getGrade(); // fallback to existing
            try {
                String examJson = examRepository.findByExamCodeIgnoreCase(result.getExamCode())
                        .map(com.exam.backend.model.Exam::getExamData).orElse(null);
                if (examJson != null) {
                    Map<String, Object> examMap = mapper.readValue(examJson, Map.class);
                    grade = resolveGrade(examMap, totalScore, totalMaxMarks);
                }
            } catch (Exception e) {
                log.warn("regenerateHtmlReport: could not re-resolve grade for result id={}: {}", result.getId(), e.getMessage());
            }

            // ── Overwrite the HTML file ───────────────────────────────────────────
            writeHtmlReport(
                    result.getSessionKey(),
                    result.getStudentName(),
                    result.getExamCode(),
                    result.getExamTitle() != null ? result.getExamTitle() : result.getExamCode(),
                    totalScore, totalMaxMarks, grade,
                    details);

            log.info("regenerateHtmlReport: HTML updated for sessionKey={} totalScore={}/{}",
                    result.getSessionKey(), totalScore, totalMaxMarks);

        } catch (IOException e) {
            log.error("regenerateHtmlReport: failed for result id={}: {}", result.getId(), e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildQuestionBlock(Map<String, Object> d) {
        String  qNum  = String.valueOf(d.getOrDefault("questionNumber", "?"));
        String  qText = esc(String.valueOf(d.getOrDefault("questionText", "")));
        String  qType = String.valueOf(d.getOrDefault("questionType", "mcq"));
        Object  totalMarks = d.get("totalMarks");

        // ── Verbal — evaluated asynchronously by AI ──────────────────────────
        if ("verbal".equalsIgnoreCase(qType)) {
        	String aiStatus   = String.valueOf(d.getOrDefault("aiStatus", "PENDING"));
            boolean evaluated = "SUCCESS".equalsIgnoreCase(aiStatus);
            Object  aiScoreObj = d.get("aiScore");
            String  transcript = d.get("transcript") != null ? esc(d.get("transcript").toString()) : null;
            String  feedback   = d.get("feedback")   != null ? esc(d.get("feedback").toString())   : null;

            String marksDisplay;
            String statusBadge;
            String bodyHtml;

            if (evaluated && aiScoreObj != null) {
                double aiScore = ((Number) aiScoreObj).doubleValue();
                marksDisplay = String.format("+%.2f / %s pts", aiScore, totalMarks);
                statusBadge  = "<span style='color:#16a34a;font-weight:bold'>&#10003; AI Evaluated</span>";
                StringBuilder vbody = new StringBuilder("<div class='subj'>");
                vbody.append("<div><strong>&#127908; Verbal answer recorded.</strong></div>");
                if (transcript != null && !transcript.isBlank()) {
                    vbody.append("<div style='margin-top:6px'><strong>Transcript:</strong><br>")
                         .append(transcript).append("</div>");
                }
                if (feedback != null && !feedback.isBlank()) {
                    vbody.append("<div style='margin-top:6px;color:#15803d'><strong>AI Feedback:</strong><br>")
                         .append(feedback).append("</div>");
                }
                vbody.append("</div>");
                bodyHtml = vbody.toString();
            } else {
                marksDisplay = "/ " + totalMarks + " pts (pending)";
                statusBadge  = "<span style='color:#ea580c;font-weight:bold'>&#127908; Verbal (pending)</span>";
                bodyHtml = "<div class='subj'><div style='color:#9a3412'>"
                         + "&#127908; Verbal answer recorded — score will be updated after AI evaluation."
                         + "</div></div>";
            }
            return """
                    <div class='q-block' style='border-color:#f97316'>
                      <div class='q-header'>
                        <span class='q-num'>Question %s</span>
                          %s
                        <span class='q-marks'>%s</span>
                      </div>
                      <p class='q-text'>%s</p>
                      %s
                    </div>
                    """.formatted(qNum, statusBadge, marksDisplay, qText, bodyHtml);
        }

        boolean correct      = Boolean.TRUE.equals(d.get("correct"));
        boolean notAttempted = d.get("userAnswer") == null;
        Object  marksAwarded = d.get("marksAwarded");

        String badge = "subjective".equalsIgnoreCase(qType)
                ? (d.get("aiScore") != null
                ? "<span class='badge-correct'>&#10003; AI Evaluated</span>"
                : "<span class='badge-skipped'>&#8213; Pending AI Evaluation</span>")
            : correct
                ? "<span class='badge-correct'>&#10003; Correct</span>"
                : notAttempted
                    ? "<span class='badge-skipped'>&#8213; Not Attempted</span>"
                    : "<span class='badge-incorrect'>&#10007; Incorrect</span>";

        String marksLabel = formatMark(marksAwarded) + " / " + totalMarks;

        StringBuilder body = new StringBuilder();

        if ("subjective".equalsIgnoreCase(qType)) {
        	 String aiStatus   = String.valueOf(d.getOrDefault("aiStatus", "PENDING"));
             boolean evaluated = "SUCCESS".equalsIgnoreCase(aiStatus);
             Object  aiScoreObj = d.get("aiScore");
             String  feedback   = d.get("feedback") != null ? esc(d.get("feedback").toString()) : null;
            String userAns = formatAnswer(d.get("userAnswer"));
            // correctAnswer is always empty for subjective (AI-scored); use expectedReply instead
            Object expectedReplyObj = d.get("expectedReply");
            String corrAns = (expectedReplyObj != null && !expectedReplyObj.toString().isBlank())
                    ? expectedReplyObj.toString()
                    : formatAnswer(d.get("correctAnswer"));
            body.append("<div class='subj'>")
                .append("<div><strong>Your answer:</strong> ").append(esc(userAns)).append("</div>")
                .append("<div class='correct-hint'><strong>Expected answer:</strong> ")
                .append(esc(corrAns)).append("</div>")
                .append("</div>");
            if (evaluated && aiScoreObj != null) {
                if (feedback != null && !feedback.isBlank()) {
                    body.append("<div style='color:#15803d;margin-top:4px'><strong>AI Feedback:</strong><br>")
                        .append(feedback).append("</div>");
                }
            } else {
                body.append("<div style='color:#9a3412;margin-top:4px'>AI evaluation pending…</div>");
            }
            body.append("</div>");
        
        } else {
            List<Object> options    = (List<Object>) d.get("options");
            List<String> correctIds = toStringList(d.get("correctAnswer"));
            List<String> userIds    = toStringList(d.get("userAnswer"));

            if (options != null) {
                for (Object optObj : options) {
                    Map<String, Object> opt = (Map<String, Object>) optObj;
                    String id   = String.valueOf(opt.getOrDefault("id", ""));
                    String type = String.valueOf(opt.getOrDefault("type", "text"));
                    String text = "image".equalsIgnoreCase(type)
                            ? "[Image option " + id.toUpperCase() + "]"
                            : esc(String.valueOf(opt.getOrDefault("text", "")));

                    boolean isCorrect  = correctIds.contains(id);
                    boolean isSelected = userIds.contains(id);

                    String cls, marker;
                    if (isCorrect && isSelected) { cls = "opt-correct-selected"; marker = "&#10003;"; }
                    else if (isCorrect)           { cls = "opt-correct";          marker = "&#10003;"; }
                    else if (isSelected)          { cls = "opt-wrong-selected";   marker = "&#10007;"; }
                    else                          { cls = "option";               marker = "&#9711;";  }

                    body.append("<div class='option ").append(cls).append("'>")
                        .append(marker).append(" (").append(id.toUpperCase()).append(") ")
                        .append(text).append("</div>");
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

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String formatMark(Object val) {
        if (val == null) return "0";
        try {
            double d = Double.parseDouble(val.toString());
            return (d >= 0 ? "+" : "") + String.format("%.2f", d);
        } catch (NumberFormatException e) {
            return val.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private String formatAnswer(Object val) {
        if (val == null) return "(Not answered)";
        if (val instanceof List<?> list) {
            return String.join(" / ", list.stream().map(Object::toString).toList());
        }
        return val.toString();
    }
}
