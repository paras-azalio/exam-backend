package com.exam.backend.controller;

import com.exam.backend.dto.VerbalUpdateRequest;
import com.exam.backend.model.AiResult;
import com.exam.backend.service.VerbalEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class VerbalResultController {

    private final VerbalEvaluationService verbalEvaluationService;

    @Value("${storage.base-path:C:/exam-recordings}")
    private String basePath;

    @Value("${verbal.webhook-secret}")
    private String webhookSecret;

    // ── Webhook — called by FastAPI after evaluation ───────────────────────────

    /**
     * POST /api/result/verbal-update
     *
     * FastAPI sends this once per verbal question after evaluating it.
     *
     * ═══════════════════════════════════════════════════════════════════════
     * REQUEST (FastAPI → Spring):
     * {
     *   "jti":          "550e8400-...",
     *   "questionId":   "q4",                  ← preferred; echo back what we sent
     *   "questionText": "Introduce yourself.",  ← fallback if questionId absent
     *   "score":        "7.5",
     *   "precision":    3,
     *   "maxMarks":     10,
     *   "secret":       "QuickScreenVerbal#2026"
     * }
     *
     * RESPONSE:
     * { "status": "updated", "aiResultId": 12 }
     * ═══════════════════════════════════════════════════════════════════════
     */
    @PostMapping("/api/result/verbal-update")
    public ResponseEntity<?> verbalUpdate(@RequestBody VerbalUpdateRequest req) {
        try {
            double score = 0.0;
            try { score = Double.parseDouble(req.getScore()); }
            catch (Exception e) { log.warn("Could not parse score '{}'; defaulting to 0", req.getScore()); }

            AiResult updated = verbalEvaluationService.applyVerbalResult(
                    req.getJti(),
                    req.getQuestionId(),
                    req.getQuestionText(),
                    score,
                    req.getScore(),   // store raw score string as response
                    req.getSecret());

            return ResponseEntity.ok(Map.of(
                    "status",     "updated",
                    "aiResultId", updated.getId()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid secret"));
        } catch (Exception e) {
            log.error("verbal-update failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Audio upload — called when verbal timer completes ─────────────────────

    /**
     * POST /api/result/audio/{sessionKey}/{questionId}?token={jwtToken}
     *
     * Saves the WebM audio blob to {basePath}/{sessionKey}/verbal_{questionId}.webm,
     * then immediately initiates the AI evaluation if a valid JWT token is supplied.
     *
     * <p>The {@code token} query param is the student's raw JWT invite token.  When
     * present, this endpoint creates the AiResult row and fires the AI API call
     * right away — before the exam is submitted — so results arrive sooner and
     * the exam-submission path only needs to recompute the total, not wait for AI.
     *
     * <p>Omitting {@code token} is supported for backwards-compatibility; in that
     * case evaluation is deferred to submission time (legacy behaviour).
     */
    @PostMapping("/api/result/audio/{sessionKey}/{questionId}")
    public ResponseEntity<?> uploadAudio(
            @PathVariable String sessionKey,
            @PathVariable String questionId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "token", required = false) String token) {
        try {
            if (sessionKey.contains("..") || questionId.contains("..")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
            }
            Path dir  = Paths.get(basePath, sessionKey);
            Files.createDirectories(dir);
            String filename = "verbal_" + questionId + ".webm";
            Path   dest     = dir.resolve(filename);
            file.transferTo(dest);
            log.info("Verbal audio saved: {}", dest);

            // Immediately initiate AI evaluation if a JWT token was supplied.
            // Any failure here is non-fatal — the evaluation is retried at submission time.
            if (token != null && !token.isBlank()) {
                try {
                    verbalEvaluationService.initVerbalEvaluationAtUpload(sessionKey, questionId, token);
                } catch (Exception e) {
                    log.warn("Early verbal initiation failed for sessionKey={} questionId={}: {}",
                             sessionKey, questionId, e.getMessage());
                }
            }

            return ResponseEntity.ok(Map.of("status", "saved", "filename", filename));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save audio: " + e.getMessage()));
        }
    }

    // ── Built-in simulation endpoint ──────────────────────────────────────────

    /**
     * POST /api/simulate/verbal-evaluate
     *
     * Mimics the FastAPI service for local testing.
     * Configure: verbal.api-url=http://localhost:8080/QuickScreen/api/simulate/verbal-evaluate
     *
     * ═══════════════════════════════════════════════════════════════════════
     * JSON CONTRACT FOR FASTAPI TEAM
     * ═══════════════════════════════════════════════════════════════════════
     *
     * REQUEST (Spring Boot → FastAPI):
     * {
     *   "jti":            "550e8400-...",
     *   "sessionKey":     "abc123",
     *   "questionId":     "q4",
     *   "questionText":   "Introduce yourself.",
     *   "expectedReply":  "Name, experience, skills...",
     *   "precision":      3,
     *   "maxMarks":       10,
     *   "audioFileUrl":   "https://host/api/admin/recordings/file?...",
     *   "callbackUrl":    "https://host/api/result/verbal-update",
     *   "callbackSecret": "QuickScreenVerbal#2026"
     * }
     *
     * FastAPI ACK response:
     * { "status": "queued", "jobId": "some-uuid" }
     *
     * FastAPI callback (to callbackUrl):
     * {
     *   "jti":          "550e8400-...",
     *   "questionId":   "q4",
     *   "questionText": "Introduce yourself.",
     *   "score":        "8.0",
     *   "precision":    3,
     *   "maxMarks":     10,
     *   "secret":       "QuickScreenVerbal#2026"
     * }
     * ═══════════════════════════════════════════════════════════════════════
     */
    @PostMapping("/api/simulate/verbal-evaluate")
    public ResponseEntity<?> simulate(@RequestBody Map<String, Object> req) {
        try {
            String jti          = String.valueOf(req.get("jti"));
            String questionId   = String.valueOf(req.getOrDefault("questionId", ""));
            String questionText = String.valueOf(req.getOrDefault("questionText", ""));
            double maxMarks     = ((Number) req.getOrDefault("maxMarks", 10)).doubleValue();
            String secret       = String.valueOf(req.getOrDefault("callbackSecret", webhookSecret));

            // Random score in range [1, maxMarks]
            double score = 1 + Math.random() * (maxMarks - 1);
            String scoreStr = String.format("%.1f", score);

            AiResult updated = verbalEvaluationService.applyVerbalResult(
                    jti, questionId, questionText, score, scoreStr, secret);

            log.info("[SIM] Assigned score {}/{} for questionId='{}' jti={}",
                     scoreStr, maxMarks, questionId, jti);

            return ResponseEntity.ok(Map.of(
                    "status",        "simulated",
                    "questionId",    questionId,
                    "scoreAssigned", scoreStr,
                    "aiResultId",    updated.getId()
            ));
        } catch (Exception e) {
            log.error("Simulation failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
