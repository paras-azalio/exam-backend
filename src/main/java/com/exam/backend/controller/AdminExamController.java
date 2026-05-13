package com.exam.backend.controller;

import com.exam.backend.dto.ExamRequest;
import com.exam.backend.dto.GenerateLinkRequest;
import com.exam.backend.dto.GenerateLinkResponse;
import com.exam.backend.model.Exam;
import com.exam.backend.model.ExamResult;
import com.exam.backend.repository.ExamResultRepository;
import com.exam.backend.service.ExamService;
import com.exam.backend.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/exams")
@RequiredArgsConstructor
public class AdminExamController {

    private final ExamService          examService;
    private final JwtService           jwtService;
    private final ExamResultRepository examResultRepository;

    @GetMapping
    public List<Exam> list() {
        return examService.listAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ExamRequest req) {
        try {
            return ResponseEntity.ok(examService.create(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ExamRequest req) {
        try {
            return ResponseEntity.ok(examService.update(id, req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        examService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Exam> toggle(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(examService.toggleActive(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Return all submitted results for a given exam. */
    @GetMapping("/{id}/results")
    public ResponseEntity<?> getResults(@PathVariable Long id) {
        try {
            Exam exam = examService.findById(id);
            List<ExamResult> results =
                    examResultRepository.findByExamCodeIgnoreCaseOrderByCreatedAtDesc(exam.getExamCode());

            List<Map<String, Object>> rows = results.stream().map(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           r.getId());
                row.put("studentName",  r.getStudentName());
                row.put("studentEmail", r.getStudentEmail());
                row.put("score",        r.getScore());
                row.put("totalMarks",   r.getTotalMarks());
                row.put("grade",        r.getGrade());
                row.put("startedAt",    r.getStartedAt());
                row.put("createdAt",    r.getCreatedAt());
                row.put("checked",      r.isChecked());
                return row;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(rows);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Generate a signed JWT invite link for a specific user + exam. */
    @PostMapping("/{id}/generate-link")
    public ResponseEntity<?> generateLink(@PathVariable Long id,
                                          @RequestBody GenerateLinkRequest req) {
        try {
            Exam exam = examService.findById(id);
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
