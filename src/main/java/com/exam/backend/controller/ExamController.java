package com.exam.backend.controller;

import com.exam.backend.repository.UsedTokenRepository;
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

    private final ExamService          examService;
    private final UsedTokenRepository  usedTokenRepository;

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
     * Lets the frontend check—before the exam starts—whether a JWT invite
     * token has already been consumed.  Returns { "used": true/false }.
     */
    @GetMapping("/check-token/{jti}")
    public ResponseEntity<Map<String, Object>> checkToken(@PathVariable String jti) {
        boolean used = usedTokenRepository.existsById(jti);
        return ResponseEntity.ok(Map.of("used", used));
    }
}
