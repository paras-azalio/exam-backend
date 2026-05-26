package com.exam.backend.controller;

import com.exam.backend.dto.SaveResultRequest;
import com.exam.backend.dto.ScoredResult;
import com.exam.backend.model.ExamResult;
import com.exam.backend.repository.ExamResultRepository;
import com.exam.backend.service.ResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/result")
@RequiredArgsConstructor
public class ResultController {

    private final ResultService         resultService;
    private final ExamResultRepository  resultRepository;

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveResult(@RequestBody SaveResultRequest req) {
    	log.info("Received request to save exam result for sessionKey: {}", req.getSessionKey());
        if (req.getSessionKey() == null || req.getSessionKey().isBlank()) {
        	log.warn("Save request rejected: sessionKey is missing or blank");
            return ResponseEntity.badRequest().body(Map.of("error", "sessionKey required"));
        }
        try {
            ScoredResult scored = resultService.saveResult(req);
            log.info("Successfully saved result for sessionKey: {}. Score: {}/{}", scored.getResult().getSessionKey(), scored.getScore(), scored.getTotalMarks());
            return ResponseEntity.ok(Map.of(
                    "status",     "saved",
                    "id",         scored.getResult().getId(),
                    "sessionKey", scored.getResult().getSessionKey(),
                    "score",      scored.getScore(),
                    "totalMarks", scored.getTotalMarks(),
                    "grade",      scored.getGrade() != null ? scored.getGrade() : "",
                    "details",    scored.getDetails()
            ));
        } catch (IllegalStateException e) {
        	log.warn("Conflict while saving result for sessionKey {}: {}", req.getSessionKey(), e.getMessage());
            // JWT token already used by a different session — 409 Conflict
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
        	log.warn("Bad request while saving result for sessionKey {}: {}", req.getSessionKey(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
        	log.error("IO Exception while saving result for sessionKey {}", req.getSessionKey(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save result: " + e.getMessage()));
        }
    }

    @GetMapping("/{sessionKey}")
    public ResponseEntity<ExamResult> getResult(@PathVariable String sessionKey) {
    	log.info("Fetching exam result for sessionKey: {}", sessionKey);
        return resultRepository.findBySessionKey(sessionKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
