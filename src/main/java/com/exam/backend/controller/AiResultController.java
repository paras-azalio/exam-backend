package com.exam.backend.controller;

import com.exam.backend.model.AiResult;
import com.exam.backend.repository.AiResultRepository;
import com.exam.backend.service.VerbalEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Admin endpoints for managing individual AI verbal evaluation records.
 */
@RestController
@RequestMapping("/api/admin/ai-results")
@RequiredArgsConstructor
public class AiResultController {

    private final AiResultRepository      aiResultRepository;
    private final VerbalEvaluationService  verbalEvaluationService;

    /**
     * POST /api/admin/ai-results/{id}/retry
     *
     * Re-fires the AI evaluation for a specific verbal question.
     *
     * Eligible when:
     *   - status = FAILED, OR
     *   - status != SUCCESS AND initiatedAt > 1 hour ago (stuck / timed out)
     *
     * Returns 400 if not yet eligible (too soon since last attempt).
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        AiResult ar = aiResultRepository.findById(id).orElse(null);
        if (ar == null) return ResponseEntity.notFound().build();

        boolean isFailed  = "FAILED".equals(ar.getStatus());
        boolean isSuccess = "SUCCESS".equals(ar.getStatus());
        boolean isStale   = !isSuccess
                && ar.getInitiatedAt() != null
                && ar.getInitiatedAt().isBefore(LocalDateTime.now().minusHours(1));

        if (!isFailed && !isStale) {
            String reason = isSuccess
                    ? "Already evaluated successfully."
                    : "Too soon — retry is available 1 hour after last attempt.";
            return ResponseEntity.badRequest().body(Map.of("error", reason));
        }

        verbalEvaluationService.retryEvaluation(ar);
        return ResponseEntity.ok(Map.of(
                "status",      "retrying",
                "aiResultId",  id,
                "questionId",  ar.getQuestionId()
        ));
    }

    /**
     * GET /api/admin/ai-results/{id}/curl
     *
     * Returns the stored curl command for manual debugging.
     */
    @GetMapping("/{id}/curl")
    public ResponseEntity<?> getCurl(@PathVariable Long id) {
        return aiResultRepository.findById(id)
                .map(ar -> ResponseEntity.ok(Map.of(
                        "aiResultId", id,
                        "questionId", ar.getQuestionId(),
                        "status",     ar.getStatus(),
                        "curl",       ar.getRequestCurl() != null ? ar.getRequestCurl() : ""
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
