package com.exam.backend.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.exam.backend.model.AiResult;
import com.exam.backend.model.AiResultType;
import com.exam.backend.model.ExamResult;
import com.exam.backend.repository.AiResultRepository;
import com.exam.backend.repository.ExamRepository;
import com.exam.backend.repository.ExamResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the full lifecycle of verbal AI evaluations.
 *
 *  ┌─ Audio upload (recording done) ────────────────────────────────────────┐
 *  │   initVerbalEvaluationAtUpload()                                        │
 *  │     ├─ decode JWT → jti, examCode                                       │
 *  │     ├─ load exam JSON → question metadata                               │
 *  │     ├─ find-or-create ExamResult (partial, no MCQ score yet)            │
 *  │     ├─ find-or-create AiResult row (PENDING)                            │
 *  │     └─ fire immediately via thread pool                                 │
 *  │                                                                         │
 *  │  saveResult() (exam submission):                                        │
 *  │     ├─ finds existing ExamResult (or creates new)                       │
 *  │     ├─ sets MCQ score fields                                            │
 *  │     └─ skips AiResult creation if rows already exist                    │
 *  │                                                                         │
 *  │  AI calls back → VerbalResultController → applyVerbalResult()           │
 *  │     ├─ find AiResult by jti + questionId                                │
 *  │     └─ set aiScore / receivedAt / status=SUCCESS  (only ai_result row)  │
 *  │                                                                         │
 *  │  Admin portal getResults():                                             │
 *  │     └─ totalScore = score + Σ ai_score (SUCCESS)  computed on the fly   │
 *  └─────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerbalEvaluationService {

    private final AiResultRepository   aiResultRepository;
    private final ExamResultRepository resultRepository;
    private final ExamRepository       examRepository;
    private final JwtService           jwtService;
    private final ObjectMapper         mapper;

    @Value("${verbal.api-url:}")
    private String verbalApiUrl;

    @Value("${verbal.callback-url}")
    private String callbackUrl;

    @Value("${verbal.webhook-secret}")
    private String webhookSecret;

    @Value("${verbal.thread-pool-size:10}")
    private int threadPoolSize;

    @Value("${storage.base-path:C:/exam-recordings}")
    private String storagePath;

    private ExecutorService executor;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)  // uvicorn doesn't support HTTP/2 upgrade
            .build();


    @PostConstruct
    public void init() {
        executor = Executors.newFixedThreadPool(threadPoolSize);
        log.info("VerbalEvaluationService thread pool initialised (size={})", threadPoolSize);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    // ── Called from ResultService after result is persisted ──────────────────

    /**
     * Submits one evaluation task per AiResult to the thread pool.
     * Returns immediately — does NOT block the HTTP response to the student.
     */
    public void fireVerbalEvaluations(List<AiResult> aiResults) {
        if (verbalApiUrl == null || verbalApiUrl.isBlank()) {
            log.info("verbal.api-url not configured — skipping verbal evaluation");
            return;
        }
        for (AiResult ar : aiResults) {
            final Long id = ar.getId();
            executor.submit(() -> fireOne(id));
        }
    }

    /**
     * Re-fires a single AiResult (retry path called from AiResultController).
     * Resets score/status before submitting so a clean evaluation is performed.
     */
    public void retryEvaluation(AiResult aiResult) {
        if (verbalApiUrl == null || verbalApiUrl.isBlank()) {
            throw new IllegalStateException("verbal.api-url not configured");
        }
        // Reset so the webhook treats this as a fresh result
        aiResult.setAiScore(null);
        aiResult.setReceivedAt(null);
        aiResult.setResponse(null);
        aiResult.setStatus("PENDING");
        aiResultRepository.save(aiResult);
        final Long id = aiResult.getId();
        executor.submit(() -> fireOne(id));
    }

    // ── Early-fire at audio upload time ──────────────────────────────────────

    /**
     * Called by {@code VerbalResultController.uploadAudio} when the verbal timer
     * completes and the student's audio blob is saved to disk.
     *
     * <p>Decodes the student's JWT to get {@code jti} and {@code examCode}, then:
     * <ol>
     *   <li>Finds the question metadata (text, maxMarks, expectedReply, precision)</li>
     *   <li>Creates a partial {@link ExamResult} row if one does not yet exist
     *       for this {@code sessionKey} — the MCQ fields will be filled in later
     *       when the student submits the exam.</li>
     *   <li>Creates an {@link AiResult} row if one does not yet exist for this
     *       {@code jti + questionId} pair.</li>
     *   <li>Fires the AI API call immediately via the thread pool.</li>
     * </ol>
     *
     * <p>This is intentionally best-effort: any exception is caught and logged
     * so the upload response is never blocked.
     *
     * @param sessionKey the student's session key
     * @param questionId the verbal question's ID (e.g. "q4")
     * @param rawToken   the raw JWT invite token (may be null — skipped if absent)
     */
    @Transactional
    public void initVerbalEvaluationAtUpload(String sessionKey, String questionId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        if (verbalApiUrl == null || verbalApiUrl.isBlank()) {
            log.debug("verbal.api-url not configured — skipping early verbal initiation");
            return;
        }

        // 1. Decode token — extract into finals so lambdas below can capture them
        final String jti;
        final String examCode;
        try {
            Map<String, Object> claims = jwtService.verifyAndExtract(rawToken);
            jti      = String.valueOf(claims.get("jti"));
            examCode = String.valueOf(claims.get("examCode"));
        } catch (Exception e) {
            log.warn("initVerbalEvaluationAtUpload: token invalid for sessionKey={} — {}",
                     sessionKey, e.getMessage());
            return;
        }

        // 2. Load exam JSON and find question metadata
        //    Use a temp variable inside the try, then pin to a final reference outside.
        final Map<String, Object> meta;
        try {
            String examJson = examRepository.findByExamCodeIgnoreCase(examCode)
                    .map(com.exam.backend.model.Exam::getExamData)
                    .orElse(null);
            if (examJson == null) {
                log.warn("initVerbalEvaluationAtUpload: exam not found for code={}", examCode);
                return;
            }
            Map<String, Object> found = findQuestionInExam(examJson, questionId);
            if (found == null) {
                log.warn("initVerbalEvaluationAtUpload: question '{}' not found in exam '{}'",
                         questionId, examCode);
                return;
            }
            meta = found;   // single assignment → effectively final
        } catch (IOException e) {
            log.error("initVerbalEvaluationAtUpload: failed to parse exam JSON", e);
            return;
        }

        // 3. Find-or-create partial ExamResult (no MCQ score yet).
        //    Never reassign the variable — just mutate the entity object so the
        //    reference stays effectively final for the lambda below.
        final ExamResult examResult = resultRepository.findBySessionKey(sessionKey)
                .orElseGet(() -> {
                    ExamResult r = new ExamResult();
                    r.setSessionKey(sessionKey);
                    r.setJti(jti);
                    r.setExamCode(examCode);
                    return resultRepository.save(r);
                });

        // Back-fill jti if this row was created before the token was available.
        // setJti + save — no variable reassignment, so examResult stays final.
        if (examResult.getJti() == null || examResult.getJti().isBlank()) {
            examResult.setJti(jti);
            resultRepository.save(examResult);
        }

        // 4. Find-or-create AiResult row
        AiResult ar = aiResultRepository.findByJtiAndQuestionId(jti, questionId)
                .orElseGet(() -> {
                    AiResult a = new AiResult();
                    a.setExamResult(examResult);          // effectively final ✓
                    a.setJti(jti);                        // effectively final ✓
                    a.setQuestionId(questionId);
                    a.setQuestion(String.valueOf(meta.getOrDefault("question", "")));      // final ✓
                    a.setMaxMarks(((Number) meta.getOrDefault("marks", 0)).doubleValue());
                    a.setExpectedReply(String.valueOf(meta.getOrDefault("expectedReply", "")));
                    a.setPrecisionLevel(((Number) meta.getOrDefault("precision", 3)).intValue());
                    a.setInputText(sessionKey + "/verbal_" + questionId + ".webm");
                    a.setType(AiResultType.VERBAL);
                    a.setStatus("PENDING");
                    return aiResultRepository.save(a);
                });

        // 5. Fire only if not already in-flight or done.
        //    IMPORTANT: submit to the executor AFTER the transaction commits.
        //    Submitting inside the transaction means the pool thread can call
        //    findByIdEager() before the INSERT has been committed to the DB,
        //    causing "AiResult #N not found".
        if ("PENDING".equals(ar.getStatus()) || "FAILED".equals(ar.getStatus())) {
            final Long arId = ar.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submit(() -> fireOne(arId));
                }
            });
            log.info("Early verbal evaluation queued (fires after commit): sessionKey={} questionId={} jti={}",
                     sessionKey, questionId, jti);
        } else {
            log.debug("initVerbalEvaluationAtUpload: skipping fire — status={} sessionKey={} questionId={}",
                      ar.getStatus(), sessionKey, questionId);
        }
    }

    // ── Core task — runs on the pool thread ──────────────────────────────────

    /**
     * Reloads the AiResult (with its parent ExamResult) fresh from DB, then fires
     * the HTTP call to the AI service.
     *
     * <p>We accept an {@code id} — not the entity — because this runs on a pool
     * thread with no active JPA session.  Passing a detached entity and calling
     * {@code save()} on it causes {@code StaleObjectStateException}.  Loading by ID
     * via {@code findByIdEager} opens a fresh transaction on the pool thread, keeping
     * everything clean.
     */
    private void fireOne(Long aiResultId) {
        // Load fresh — avoids detached-entity and LazyInitializationException issues
        AiResult aiResult = aiResultRepository.findByIdEager(aiResultId).orElse(null);
        if (aiResult == null) {
            log.warn("fireOne: AiResult #{} not found in DB — skipping", aiResultId);
            return;
        }
        try {
            String sessionKey = aiResult.getExamResult().getSessionKey(); // eagerly loaded ✓

            // Build JSON payload
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jti",            aiResult.getJti());
            payload.put("sessionKey",     sessionKey);
            payload.put("questionId",     aiResult.getQuestionId());
            payload.put("questionText",   aiResult.getQuestion());
            if (AiResultType.SUBJECTIVE.equals(aiResult.getType())) {
                List<String> expectedList = Arrays.asList(
                    aiResult.getExpectedReply().split("\\|\\|\\|")
                );
                payload.put("expectedReply", expectedList);
            } else {
                payload.put("expectedReply", aiResult.getExpectedReply());
            }
            payload.put("precision",      aiResult.getPrecisionLevel());
            payload.put("maxMarks",       aiResult.getMaxMarks());
            if (AiResultType.SUBJECTIVE.equals(aiResult.getType())) {
                payload.put("inputText", aiResult.getInputText());
            } else {
            	payload.put("audioFilePath", storagePath + "/" + aiResult.getInputText());
            }
            payload.put("type", aiResult.getType());          
            payload.put("callbackUrl",    callbackUrl);
            payload.put("callbackSecret", webhookSecret);

            String body = mapper.writeValueAsString(payload);

            // Curl command for manual debugging / admin retry reference
            String curl = "curl -X POST \"" + verbalApiUrl + "\""
                    + " -H \"Content-Type: application/json\""
                    + " -d '" + body.replace("'", "'\\''") + "'";

            // Persist: mark SENT + record curl + initiatedAt
            aiResult.setRequestCurl(curl);
            aiResult.setInitiatedAt(LocalDateTime.now());
            aiResult.setStatus("SENT");
            aiResultRepository.save(aiResult);

            // Fire HTTP POST — waits only for acknowledgement (score comes via webhook)
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(verbalApiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            log.info("Sending to AI: {}", body);

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Verbal eval fired: questionId={} jti={} → HTTP {}",
                     aiResult.getQuestionId(), aiResult.getJti(), resp.statusCode());

            // Always store the raw response for DB analysis
            aiResult.setResponse(resp.body());

            if (resp.statusCode() >= 400) {
                log.error("AI returned {}: {}", resp.statusCode(), resp.body());
                aiResult.setStatus("FAILED");
            } else {
                // Check if the API returned the score inline (e.g. simulate mode)
                // instead of via a separate webhook callback.
                try {
                    Map<String, Object> respJson = mapper.readValue(resp.body(), Map.class);
                    Object scoreRaw = respJson.get("scoreAssigned");
                    if (scoreRaw != null) {
                        double inlineScore = Double.parseDouble(scoreRaw.toString());
                        aiResult.setAiScore(inlineScore);
                        aiResult.setReceivedAt(LocalDateTime.now());
                        aiResult.setStatus("SUCCESS");
                        log.info("Verbal score applied inline: questionId={} score={}",
                                 aiResult.getQuestionId(), inlineScore);
                    }
                } catch (Exception ignored) {
                    // Response is not JSON or has no scoreAssigned — webhook path, nothing to do
                }
            }
            aiResultRepository.save(aiResult);

        } catch (Exception e) {
            log.error("Failed to fire verbal evaluation for questionId={} jti={}: {}",
                      aiResult.getQuestionId(), aiResult.getJti(), e.getMessage());
            aiResult.setStatus("FAILED");
            aiResult.setResponse(e.getMessage());
            aiResultRepository.save(aiResult);
        }
    }

    // ── Webhook callback — called from VerbalResultController ────────────────

    /**
     * Records the AI-assigned score for one verbal question.
     *
     * <p>Only the {@code ai_result} row is updated — {@code exam_results} is not
     * touched at all.  The admin portal computes the combined total on the fly
     * by summing the {@code ai_score} values from this table.  This means:
     * <ul>
     *   <li>No race conditions — concurrent callbacks for different questions
     *       of the same exam write to completely independent rows.</li>
     *   <li>Retry correctness is automatic — the old row is simply overwritten.</li>
     *   <li>No need for atomic SQL tricks or sequence numbers.</li>
     * </ul>
     *
     * @return the updated {@link AiResult}
     */
    public AiResult applyVerbalResult(String jti,
                                      String questionId,
                                      String questionText,
                                      double score,
                                      String fullJsonResponse,
                                      String transcript,
                                      String feedback,
                                      String secret) {
        if (!webhookSecret.equals(secret)) {
            throw new SecurityException("Invalid webhook secret");
        }

        // Find the AiResult row
        AiResult ar;
        if (questionId != null && !questionId.isBlank()) {
            ar = aiResultRepository.findByJtiAndQuestionId(jti, questionId)
                    .orElseGet(() -> aiResultRepository.findByJtiAndQuestion(jti, questionText)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "No AiResult found for jti=" + jti + " questionId=" + questionId)));
        } else {
            ar = aiResultRepository.findByJtiAndQuestion(jti, questionText)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No AiResult found for jti=" + jti + " question=" + questionText));
        }

        // Update AiResult fields only — exam_results is untouched
        ar.setAiScore(score);
        ar.setResponse(fullJsonResponse);   // full callback JSON for audit trail
        ar.setTranscript(transcript);
        ar.setFeedback(feedback);
        ar.setReceivedAt(LocalDateTime.now());
        ar.setStatus("SUCCESS");
        aiResultRepository.save(ar);

        log.info("Verbal result recorded: jti={} questionId={} score={} transcript_len={}",
                 jti, ar.getQuestionId(), score,
                 transcript != null ? transcript.length() : 0);

        return ar;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Walks the exam JSON and returns the question map whose {@code id} matches, or null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findQuestionInExam(String examJson, String questionId)
            throws IOException {
        Map<String, Object> examMap = mapper.readValue(examJson, Map.class);
        List<Object> sections = (List<Object>) examMap.get("sections");
        if (sections == null) return null;
        for (Object sObj : sections) {
            Map<String, Object> section = (Map<String, Object>) sObj;
            List<Object> questions = (List<Object>) section.get("questions");
            if (questions == null) continue;
            for (Object qObj : questions) {
                Map<String, Object> q = (Map<String, Object>) qObj;
                if (questionId.equals(String.valueOf(q.get("id")))) return q;
            }
        }
        return null;
    }
}
