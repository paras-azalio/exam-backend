package com.exam.backend.controller;

import com.exam.backend.repository.UsedTokenRepository;
import com.exam.backend.service.ExamService;
import com.exam.backend.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exam")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService          examService;
    private final JwtService           jwtService;
    private final UsedTokenRepository  usedTokenRepository;

    /**
     * Returns exam metadata only (title, duration, recording config, etc.).
     * The sections / questions array is intentionally omitted so that question
     * text is never visible in the network tab before the exam starts.
     */
    @GetMapping("/{examCode}")
    public ResponseEntity<Map<String, Object>> getExam(@PathVariable String examCode) {
        try {
            Map<String, Object> exam = examService.getActiveExam(examCode);
            return exam != null ? ResponseEntity.ok(exam) : ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns exam sections (questions + options, no correct answers).
     * Requires a valid JWT invite token in the Authorization: Bearer header.
     * The token's examCode claim must match the URL parameter.
     * Called only when the student clicks "Start Exam" — after recording setup
     * and disclaimer, so questions are never exposed during setup phases.
     */
    @GetMapping("/{examCode}/questions")
    public ResponseEntity<?> getQuestions(
            @PathVariable String examCode,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Authorization required"));
            }

            String token  = authHeader.substring(7);
            Map<String, Object> claims = jwtService.verifyAndExtract(token);

            // Cross-check: token must be issued for this exact exam
            String tokenExamCode = String.valueOf(claims.getOrDefault("examCode", ""));
            if (!tokenExamCode.equalsIgnoreCase(examCode)) {
                return ResponseEntity.status(403).body(Map.of("error", "Token does not match exam"));
            }

            List<Object> sections = examService.getExamSections(examCode);
            if (sections == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("sections", sections));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Lets the frontend check — before the exam starts — whether a JWT invite
     * token has already been consumed.  Returns { "used": true/false }.
     */
    @GetMapping("/check-token/{jti}")
    public ResponseEntity<Map<String, Object>> checkToken(@PathVariable String jti) {
        boolean used = usedTokenRepository.existsById(jti);
        return ResponseEntity.ok(Map.of("used", used));
    }
}
