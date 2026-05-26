package com.exam.backend.controller;

import com.exam.backend.model.AiResult;
import com.exam.backend.repository.AiResultRepository;
import com.exam.backend.service.VerbalEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Admin endpoints for managing individual AI verbal evaluation records.
 */

@Slf4j
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
    	log.info("Received request to retry AI evaluation for result ID: {}", id);
        AiResult ar = aiResultRepository.findById(id).orElse(null);
        if (ar == null) {
        	log.warn("AiResult ID {} not found for retry request", id);
        	return ResponseEntity.notFound().build();
        }

        boolean isFailed  = "FAILED".equals(ar.getStatus());
        boolean isSuccess = "SUCCESS".equals(ar.getStatus());
        boolean isStale   = !isSuccess
                && ar.getInitiatedAt() != null
                && ar.getInitiatedAt().isBefore(LocalDateTime.now().minusHours(1));

        if (!isFailed && !isStale) {
            String reason = isSuccess
                    ? "Already evaluated successfully."
                    : "Too soon — retry is available 1 hour after last attempt.";
            log.warn("Retry rejected for AiResult ID {}: {}", id, reason);
            return ResponseEntity.badRequest().body(Map.of("error", reason));
        }
        
        log.info("Initiating retry evaluation for AiResult ID {}", id);
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
    	log.info("Fetching curl command for AiResult ID: {}", id);
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
