package com.exam.backend.service;

import com.exam.backend.dto.ExamRequest;
import com.exam.backend.model.Exam;
import com.exam.backend.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final ObjectMapper mapper;

    // ── Public ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getActiveExam(String examCode) throws IOException {
        // Excludes trashed exams in addition to inactive ones
        return examRepository.findByExamCodeIgnoreCaseAndActiveTrueAndDeletedAtIsNull(examCode)
                .map(e -> {
                    try { return mapper.readValue(e.getExamData(), Map.class); }
                    catch (IOException ex) { throw new RuntimeException(ex); }
                })
                .orElse(null);
    }

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    /** Returns all live (non-trashed) exams. */
    public List<Exam> listAll() {
        return examRepository.findByDeletedAtIsNullOrderByCreatedAtDesc();
    }

    /** Returns all soft-deleted exams (the trash bin). */
    public List<Exam> listTrashed() {
        return examRepository.findByDeletedAtIsNotNullOrderByDeletedAtDesc();
    }

    @SuppressWarnings("unchecked")
    public Exam create(ExamRequest req) throws IOException {
        Map<String, Object> json = mapper.readValue(req.getExamData(), Map.class);
        String code  = String.valueOf(json.get("examCode")).toUpperCase();
        String title = String.valueOf(json.getOrDefault("examTitle", ""));

        if (examRepository.existsByExamCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("Exam code '" + code + "' already exists.");
        }

        Exam exam = new Exam();
        exam.setExamCode(code);
        exam.setExamTitle(title);
        exam.setExamData(req.getExamData());
        exam.setActive(req.isActive());
        return examRepository.save(exam);
    }

    @SuppressWarnings("unchecked")
    public Exam update(Long id, ExamRequest req) throws IOException {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));

        Map<String, Object> json = mapper.readValue(req.getExamData(), Map.class);
        String code  = String.valueOf(json.get("examCode")).toUpperCase();
        String title = String.valueOf(json.getOrDefault("examTitle", ""));

        if (examRepository.existsByExamCodeIgnoreCaseAndIdNot(code, id)) {
            throw new IllegalArgumentException("Exam code '" + code + "' already exists.");
        }

        exam.setExamCode(code);
        exam.setExamTitle(title);
        exam.setExamData(req.getExamData());
        exam.setActive(req.isActive());
        return examRepository.save(exam);
    }

    public Exam findById(Long id) {
        return examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
    }

    /** Soft-delete: moves the exam to the trash. */
    public void softDelete(Long id) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
        exam.setDeletedAt(java.time.LocalDateTime.now());
        examRepository.save(exam);
    }

    /** Restore a trashed exam back to the live list. */
    public Exam restore(Long id) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
        exam.setDeletedAt(null);
        return examRepository.save(exam);
    }

    /** Permanently removes the exam from the database. */
    public void deletePermanently(Long id) {
        examRepository.deleteById(id);
    }

    public Exam toggleActive(Long id) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
        exam.setActive(!exam.isActive());
        return examRepository.save(exam);
    }
}
