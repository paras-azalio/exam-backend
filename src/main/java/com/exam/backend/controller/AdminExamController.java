package com.exam.backend.controller;

import com.exam.backend.dto.ExamRequest;
import com.exam.backend.dto.GenerateLinkRequest;
import com.exam.backend.dto.GenerateLinkResponse;
import com.exam.backend.model.AiResult;
import com.exam.backend.model.Exam;
import com.exam.backend.model.ExamResult;
import com.exam.backend.repository.AiResultRepository;
import com.exam.backend.repository.ExamResultRepository;
import com.exam.backend.service.ExamService;
import com.exam.backend.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin/exams")
@RequiredArgsConstructor
public class AdminExamController {

    private final ExamService          examService;
    private final JwtService           jwtService;
    private final ExamResultRepository examResultRepository;
    private final AiResultRepository   aiResultRepository;

    @GetMapping
    public List<Exam> list() {
    	log.info("Fetching all exams");
        return examService.listAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ExamRequest req) {
    	log.info("Creating new exam");
        try {
            return ResponseEntity.ok(examService.create(req));
        } catch (IllegalArgumentException e) {
        	log.warn("Invalid argument during exam creation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
        	log.error("JSON parsing error during exam creation", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ExamRequest req) {
    	log.info("Updating exam ID: {}", id);
        try {
            return ResponseEntity.ok(examService.update(id, req));
        } catch (IllegalArgumentException e) {
        	log.warn("Invalid argument during exam update for ID {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
        	log.error("JSON parsing error during exam update for ID {}", id, e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }

    /** Soft-delete: moves the exam to the trash bin. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDelete(@PathVariable Long id) {
    	log.info("Soft-deleting exam ID: {}", id);
        try {
            examService.softDelete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
        	log.warn("Exam not found for soft-delete with ID: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /** Returns all soft-deleted exams (trash bin). */
    @GetMapping("/trash")
    public List<Exam> listTrashed() {
    	log.info("Fetching trashed exams");
        return examService.listTrashed();
    }

    /** Restores an exam from the trash back to the live list. */
    @PatchMapping("/{id}/restore")
    public ResponseEntity<Exam> restore(@PathVariable Long id) {
    	log.info("Restoring exam ID: {}", id);
        try {
            return ResponseEntity.ok(examService.restore(id));
        } catch (IllegalArgumentException e) {
        	log.warn("Exam not found for restore with ID: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /** Permanently deletes an exam from the database. */
    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<Void> deletePermanently(@PathVariable Long id) {
    	log.info("Permanently deleting exam ID: {}", id);
        try {
            examService.deletePermanently(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
        	log.error("Error permanently deleting exam ID: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Exam> toggle(@PathVariable Long id) {
    	log.info("Toggling active status for exam ID: {}", id);
        try {
            return ResponseEntity.ok(examService.toggleActive(id));
        } catch (IllegalArgumentException e) {
        	log.warn("Exam not found for toggle with ID: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /** Return all submitted results for a given exam. */
    @GetMapping("/{id}/results")
    public ResponseEntity<?> getResults(@PathVariable Long id) {
    	log.info("Fetching results for exam ID: {}", id);
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
                row.put("violations",   r.getViolations());

                // Verbal AI evaluation records for this submission
                List<AiResult> aiResults = aiResultRepository.findByExamResult(r);
                List<Map<String, Object>> aiRows = aiResults.stream().map(ar -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id",            ar.getId());
                    a.put("questionId",    ar.getQuestionId());
                    a.put("question",      ar.getQuestion());
                    a.put("precisionLevel", ar.getPrecisionLevel());
                    a.put("aiScore",       ar.getAiScore());
                    a.put("maxMarks",      ar.getMaxMarks());
                    a.put("expectedReply", ar.getExpectedReply());
                    a.put("inputText", ar.getInputText());
                    a.put("type",      ar.getType());
                    a.put("initiatedAt",   ar.getInitiatedAt());
                    a.put("receivedAt",    ar.getReceivedAt());
                    a.put("status",        ar.getStatus());
                    a.put("transcript",    ar.getTranscript());
                    a.put("feedback",      ar.getFeedback());
                    // requestCurl and raw response omitted — available via GET /ai-results/{id}/curl
                    return a;
                }).collect(Collectors.toList());
                row.put("aiResults", aiRows);
                row.put("answersJson", r.getAnswersJson());

                // totalScore and totalMaxMarks: computed here so the frontend gets
                // a single accurate number without any client-side arithmetic.
                // totalScore  = MCQ score + Σ ai_score (SUCCESS rows only)
                // totalMaxMarks = MCQ totalMarks + Σ maxMarks (all verbal questions)
                double verbalScore = aiResults.stream()
                        .filter(ar -> "SUCCESS".equals(ar.getStatus()) && ar.getAiScore() != null)
                        .mapToDouble(AiResult::getAiScore)
                        .sum();
                double verbalMaxMarks = aiResults.stream()
                        .filter(ar -> ar.getMaxMarks() != null)
                        .mapToDouble(AiResult::getMaxMarks)
                        .sum();
                double mcqScore    = r.getScore()      != null ? r.getScore()      : 0.0;
                double mcqMax      = r.getTotalMarks()  != null ? r.getTotalMarks() : 0.0;
                double totalScore    = Math.round((mcqScore + verbalScore) * 100.0) / 100.0;
                double totalMaxMarks = mcqMax + verbalMaxMarks;
                row.put("totalScore",    totalScore);
                row.put("totalMaxMarks", totalMaxMarks);

                return row;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(rows);
        } catch (IllegalArgumentException e) {
        	log.warn("Exam not found to fetch results for ID: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /** Generate a signed JWT invite link for a specific user + exam. */
    @PostMapping("/{id}/generate-link")
    public ResponseEntity<?> generateLink(@PathVariable Long id,
                                          @RequestBody GenerateLinkRequest req) {
    	log.info("Generating JWT link for exam ID: {} and user: {}", id, req.getUserEmail());
        try {
            Exam exam = examService.findById(id);
            String link = jwtService.generateLink(
                    req.getUserName(), req.getUserEmail(), exam.getExamCode(),
                    req.getValidForMinutes(), req.getValidFromIso(), req.getValidUntilIso(),
                    req.isLiveStream());
            String expiresAt = jwtService.computeExpiresAt(req.getValidForMinutes(), req.getValidUntilIso());
            String validFrom = jwtService.computeValidFrom(req.getValidFromIso());
            return ResponseEntity.ok(new GenerateLinkResponse(link, expiresAt, validFrom));
        } catch (IllegalArgumentException e) {
        	log.warn("Error generating link for exam ID {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
