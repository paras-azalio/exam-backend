package com.exam.backend.controller;

import com.exam.backend.dto.ExamRequest;
import com.exam.backend.model.Exam;
import com.exam.backend.service.ExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/exams")
@RequiredArgsConstructor
public class AdminExamController {

    private final ExamService examService;

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
}
