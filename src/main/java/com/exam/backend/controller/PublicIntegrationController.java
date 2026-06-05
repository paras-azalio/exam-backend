package com.exam.backend.controller;

import com.exam.backend.dto.GenerateLinkRequest;
import com.exam.backend.model.AiResult;
import com.exam.backend.model.Exam;
import com.exam.backend.model.ExamResult;
import com.exam.backend.repository.AiResultRepository;
import com.exam.backend.repository.ExamRepository;
import com.exam.backend.repository.ExamResultRepository;
import com.exam.backend.service.ExamService;
import com.exam.backend.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OPEN integration API for an external portal.
 *
 * These endpoints live under /api/public/** which is NOT covered by
 * {@code AdminAuthInterceptor} (that guards only /api/admin/**), so by default
 * they require NO authentication — as requested for the dropdown integration.
 *
 * Optional hardening: set {@code public-api.key} in application.properties to a
 * non-empty value and the caller must then send it in the {@code X-Api-Key}
 * header. Leaving it blank (the default) keeps the endpoints fully open.
 *
 * Endpoints:
 *   GET  /api/public/exams
 *        → [{ examCode, examTitle, active }]  (for the portal's dropdown)
 *   POST /api/public/exams/{examCode}/generate-link
 *        → { link, jti, expiresAt, validFrom }  (same body as the admin portal)
 *   GET  /api/public/results?email=&examCode=
 *        → full result(s) incl. every ai_result row, jti, and all columns.
 *
 * Extensibility: result payloads are produced by reflecting over the JPA
 * entities ({@code ObjectMapper.convertValue}), so any column added to
 * exam_results or ai_result later is returned automatically — no code change
 * needed here. Only known-sensitive fields are explicitly stripped.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicIntegrationController {

    private final ExamService          examService;
    private final ExamRepository       examRepository;
    private final ExamResultRepository examResultRepository;
    private final AiResultRepository   aiResultRepository;
    private final JwtService           jwtService;
    private final ObjectMapper         mapper;

    /** Optional shared secret. Blank (default) = endpoints are fully open. */
    @Value("${public-api.key:}")
    private String apiKey;

    /** ai_result columns never exposed on the open API (may contain internal URLs / secrets). */
    private static final Set<String> AI_HIDDEN_FIELDS = Set.of("examResult", "requestCurl");

    /** exam_results columns never exposed on the open API (internal server path). */
    private static final Set<String> RESULT_HIDDEN_FIELDS = Set.of("pdfPath");

    // ── 1. Exam dropdown ────────────────────────────────────────────────────────

    /** Lightweight list of live exams for the external portal's dropdown. */
    @GetMapping("/exams")
    public ResponseEntity<?> listExams(@RequestHeader(value = "X-Api-Key", required = false) String key) {
        if (unauthorized(key)) return unauthorizedResponse();

        List<Map<String, Object>> exams = examService.listAll().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("examCode",  e.getExamCode());
            m.put("examTitle", e.getExamTitle());
            m.put("active",    e.isActive());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(exams);
    }

    // ── 2. Generate invite link ───────────────────────────────────────────────────

    /**
     * Mints a signed exam-invite link — identical behaviour to the admin portal's
     * generate-link, but the exam is identified by code (what the dropdown supplies)
     * instead of DB id.
     */
    @PostMapping("/exams/{examCode}/generate-link")
    public ResponseEntity<?> generateLink(@PathVariable String examCode,
                                          @RequestBody GenerateLinkRequest req,
                                          @RequestHeader(value = "X-Api-Key", required = false) String key) {
        if (unauthorized(key)) return unauthorizedResponse();

        Optional<Exam> exam = examRepository.findByExamCodeIgnoreCase(examCode);
        if (exam.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Exam not found: " + examCode));
        }

        if (req.getUserName() == null || req.getUserName().isBlank()
                || req.getUserEmail() == null || req.getUserEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userName and userEmail are required"));
        }

        try {
            String code = exam.get().getExamCode();
            String link = jwtService.generateLink(
                    req.getUserName(), req.getUserEmail(), code,
                    req.getValidForMinutes(), req.getValidFromIso(), req.getValidUntilIso());

            String expiresAt = jwtService.computeExpiresAt(req.getValidForMinutes(), req.getValidUntilIso());
            String validFrom = jwtService.computeValidFrom(req.getValidFromIso());

            // Surface the jti so the portal can later correlate / fetch by it if desired.
            String jti = extractJti(link);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("link",      link);
            resp.put("jti",       jti);
            resp.put("examCode",  code);
            resp.put("expiresAt", expiresAt);
            resp.put("validFrom", validFrom);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 3. Fetch full result by email + exam code ─────────────────────────────────

    /**
     * Returns every submission matching email + exam code (newest first), each with
     * its complete column set, computed totals, and all ai_result rows.
     *
     * A candidate may have multiple submissions (retakes) — hence a list.
     */
    @GetMapping("/results")
    public ResponseEntity<?> getResults(@RequestParam String email,
                                        @RequestParam String examCode,
                                        @RequestHeader(value = "X-Api-Key", required = false) String key) {
        if (unauthorized(key)) return unauthorizedResponse();

        if (email == null || email.isBlank() || examCode == null || examCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email and examCode are required"));
        }

        List<ExamResult> results = examResultRepository
                .findByStudentEmailIgnoreCaseAndExamCodeIgnoreCaseOrderByCreatedAtDesc(email, examCode);

        List<Map<String, Object>> payload = results.stream()
                .map(this::buildFullResult)
                .collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("email",    email);
        resp.put("examCode", examCode);
        resp.put("count",    payload.size());
        resp.put("results",  payload);
        return ResponseEntity.ok(resp);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Converts an ExamResult (and its ai_result rows) into a fully-detailed map.
     * Uses reflection-based conversion so newly added DB columns appear automatically.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFullResult(ExamResult r) {
        // Reflect the whole entity → map (auto-includes jti, answersJson, future columns)
        Map<String, Object> row = mapper.convertValue(r, LinkedHashMap.class);
        RESULT_HIDDEN_FIELDS.forEach(row::remove);

        // Attach all verbal AI evaluations for this submission
        List<AiResult> aiResults = aiResultRepository.findByExamResult(r);
        List<Map<String, Object>> aiRows = aiResults.stream().map(ar -> {
            Map<String, Object> a = mapper.convertValue(ar, LinkedHashMap.class);
            AI_HIDDEN_FIELDS.forEach(a::remove);   // strip back-reference + internal curl
            return a;
        }).collect(Collectors.toList());
        row.put("aiResults", aiRows);

        // Computed totals — same formula as the admin results endpoint
        double verbalScore = aiResults.stream()
                .filter(ar -> "SUCCESS".equals(ar.getStatus()) && ar.getAiScore() != null)
                .mapToDouble(AiResult::getAiScore).sum();
        double verbalMaxMarks = aiResults.stream()
                .filter(ar -> ar.getMaxMarks() != null)
                .mapToDouble(AiResult::getMaxMarks).sum();
        double mcqScore = r.getScore()      != null ? r.getScore()      : 0.0;
        double mcqMax   = r.getTotalMarks() != null ? r.getTotalMarks() : 0.0;

        row.put("totalScore",    Math.round((mcqScore + verbalScore) * 100.0) / 100.0);
        row.put("totalMaxMarks", mcqMax + verbalMaxMarks);
        return row;
    }

    /** Pulls the jti claim out of a freshly generated invite link. */
    private String extractJti(String link) {
        try {
            int idx = link.indexOf("usr=");
            if (idx < 0) return null;
            String token = link.substring(idx + 4);
            Object jti = jwtService.verifyAndExtract(token).get("jti");
            return jti != null ? jti.toString() : null;
        } catch (Exception e) {
            return null;   // non-fatal — link is still valid
        }
    }

    private boolean unauthorized(String providedKey) {
        if (apiKey == null || apiKey.isBlank()) return false;   // open mode
        return !apiKey.equals(providedKey);
    }

    private ResponseEntity<?> unauthorizedResponse() {
        return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing X-Api-Key"));
    }
}
