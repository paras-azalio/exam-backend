package com.exam.backend.controller;

import com.exam.backend.dto.GenerateLinkRequest;
import com.exam.backend.dto.GenerateLinkResponse;
import com.exam.backend.model.Exam;
import com.exam.backend.service.ExamService;
import com.exam.backend.service.JwtService;
import com.exam.backend.service.SebConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Admin endpoint that generates a Safe Exam Browser (.seb) config file.
 *
 * Flow:
 *   1. Admin POSTs candidate details + exam ID  (same payload as generate-link)
 *   2. Backend creates a signed JWT invite link  (re-uses JwtService)
 *   3. Backend wraps the link inside a .seb XML config
 *   4. Returns the .seb file as a download
 *
 * The admin can then email the .seb file to the candidate.
 * Candidate double-clicks it → SEB opens → navigates directly to the exam.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/exams")
@RequiredArgsConstructor
public class SebConfigController {

    private final ExamService      examService;
    private final JwtService       jwtService;
    private final SebConfigService sebConfigService;

    /**
     * POST /api/admin/exams/{id}/seb-config
     *
     * Request body: same as generate-link
     * {
     *   "userName":        "Alice Smith",
     *   "userEmail":       "alice@example.com",
     *   "validForMinutes": 1440,
     *   "validFromIso":    null,
     *   "validUntilIso":   null
     * }
     *
     * Response: application/octet-stream  (the .seb XML file download)
     */
    @PostMapping("/{id}/seb-config")
    public ResponseEntity<?> generateSebConfig(
            @PathVariable Long id,
            @RequestBody GenerateLinkRequest req) {

        log.info("Generating SEB config for exam ID={} user={}", id, req.getUserEmail());

        try {
            Exam exam = examService.findById(id);

            // 1. Generate the signed JWT invite link — with sebRequired=true embedded in the token
            String inviteLink = jwtService.generateLink(
                    req.getUserName(), req.getUserEmail(), exam.getExamCode(),
                    req.getValidForMinutes(), req.getValidFromIso(), req.getValidUntilIso(), true);

            // 2. Build the .seb XML config embedding that link
            String sebXml = sebConfigService.generateSebConfig(inviteLink, exam.getExamCode());

            // 3. Return as a downloadable file
            byte[] bytes    = sebXml.getBytes(StandardCharsets.UTF_8);
            String filename = exam.getExamCode().toLowerCase() + "_" +
                              req.getUserName().trim().replace(" ", "_").toLowerCase() +
                              ".seb";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(bytes.length)
                    .body(bytes);

        } catch (IllegalArgumentException e) {
            log.warn("SEB config generation failed for exam ID={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error generating SEB config for exam ID={}", id, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to generate SEB config: " + e.getMessage()));
        }
    }

    /**
     * GET /api/admin/exams/{id}/seb-link
     *
     * Lightweight helper — returns { link, expiresAt, validFrom } just like
     * generate-link, so the admin can see what URL will be embedded in the .seb file
     * before downloading it.  Useful for debugging / preview.
     */
    @PostMapping("/{id}/seb-link")
    public ResponseEntity<?> previewSebLink(
            @PathVariable Long id,
            @RequestBody GenerateLinkRequest req) {
        try {
            Exam   exam      = examService.findById(id);
            String link      = jwtService.generateLink(
                    req.getUserName(), req.getUserEmail(), exam.getExamCode(),
                    req.getValidForMinutes(), req.getValidFromIso(), req.getValidUntilIso());
            String expiresAt = jwtService.computeExpiresAt(req.getValidForMinutes(), req.getValidUntilIso());
            String validFrom = jwtService.computeValidFrom(req.getValidFromIso());
            return ResponseEntity.ok(new GenerateLinkResponse(link, expiresAt, validFrom));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
