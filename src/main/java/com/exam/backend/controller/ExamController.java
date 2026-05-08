package com.exam.backend.controller;

import com.exam.backend.service.ExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/exam")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;

    @GetMapping("/{examCode}")
    public ResponseEntity<Map<String, Object>> getExam(@PathVariable String examCode) {
        try {
            Map<String, Object> exam = examService.getActiveExam(examCode);
            return exam != null ? ResponseEntity.ok(exam) : ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
