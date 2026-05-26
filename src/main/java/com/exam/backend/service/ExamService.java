package com.exam.backend.service;

import com.exam.backend.dto.ExamRequest;
import com.exam.backend.model.Exam;
import com.exam.backend.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final ObjectMapper mapper;

    // ── Public ────────────────────────────────────────────────────────────────

    /**
     * Returns exam metadata (title, duration, config, etc.) with the sections array
     * removed entirely.  Questions are only revealed via {@link #getExamSections} which
     * requires a valid JWT — so the full question list is never accessible in the
     * network tab during the Recording Setup / Disclaimer phases.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getActiveExam(String examCode) throws IOException {
    	log.debug("Fetching active exam metadata for code: {}", examCode);
        return examRepository.findByExamCodeIgnoreCaseAndActiveTrueAndDeletedAtIsNull(examCode)
                .map(e -> {
                    try {
                        Map<String, Object> examMap = mapper.readValue(e.getExamData(), Map.class);
                        examMap.remove("sections"); // fetched separately once exam actually starts
                        return examMap;
                    }
                    catch (IOException ex) { 
                    	log.error("Failed to parse exam data for active exam code: {}", examCode, ex);
                    	throw new RuntimeException(ex); 
                    	}
                })
                .orElse(null);
    }

    /**
     * Returns the sections array (with questions) for an active exam.
     * {@code correctAnswer} is stripped from every question — scoring is server-side.
     * This must only be called after the JWT has been validated by the controller.
     */
    @SuppressWarnings("unchecked")
    public List<Object> getExamSections(String examCode) throws IOException {
    	log.debug("Fetching exam sections for code: {}", examCode);
        return examRepository.findByExamCodeIgnoreCaseAndActiveTrueAndDeletedAtIsNull(examCode)
                .map(e -> {
                    try {
                        Map<String, Object> examMap = mapper.readValue(e.getExamData(), Map.class);
                        List<Object> sections = (List<Object>) examMap.get("sections");
                        if (sections != null) stripCorrectAnswers(sections);
                        return sections;
                    }
                    catch (IOException ex) { 
                    	log.error("Failed to parse exam sections for code: {}", examCode, ex);
                    	throw new RuntimeException(ex); 
                    	}
                })
                .orElse(null);
    }

    /** Removes correctAnswer from every question in a sections list. */
    @SuppressWarnings("unchecked")
    private void stripCorrectAnswers(List<Object> sections) {
    	log.trace("Stripping correct answers from exam sections");
        for (Object sectionObj : sections) {
            Map<String, Object> section = (Map<String, Object>) sectionObj;
            List<Object> questions = (List<Object>) section.get("questions");
            if (questions == null) continue;
            for (Object qObj : questions) {
                ((Map<String, Object>) qObj).remove("correctAnswer");
            }
        }
    }

    // ── Admin CRUD ────────────────────────────────────────────────────────────

    /** Returns all live (non-trashed) exams. */
    public List<Exam> listAll() {
    	log.debug("Listing all active (non-trashed) exams");
        return examRepository.findByDeletedAtIsNullOrderByCreatedAtDesc();
    }

    /** Returns all soft-deleted exams (the trash bin). */
    public List<Exam> listTrashed() {
    	log.debug("Listing all soft-deleted exams");
        return examRepository.findByDeletedAtIsNotNullOrderByDeletedAtDesc();
    }

    @SuppressWarnings("unchecked")
    public Exam create(ExamRequest req) throws IOException {
    	log.info("Creating a new exam");
        Map<String, Object> json = mapper.readValue(req.getExamData(), Map.class);
        String code  = String.valueOf(json.get("examCode")).toUpperCase();
        String title = String.valueOf(json.getOrDefault("examTitle", ""));

        if (examRepository.existsByExamCodeIgnoreCase(code)) {
        	log.warn("Attempt to create exam with duplicate code: {}", code);
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
    	log.info("Updating exam with ID: {}", id);
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));

        Map<String, Object> json = mapper.readValue(req.getExamData(), Map.class);
        String code  = String.valueOf(json.get("examCode")).toUpperCase();
        String title = String.valueOf(json.getOrDefault("examTitle", ""));

        if (examRepository.existsByExamCodeIgnoreCaseAndIdNot(code, id)) {
        	log.warn("Attempt to update exam ID {} with duplicate code: {}", id, code);
            throw new IllegalArgumentException("Exam code '" + code + "' already exists.");
        }
        exam.setExamCode(code);
        exam.setExamTitle(title);
        exam.setExamData(req.getExamData());
        exam.setActive(req.isActive());
        log.info("Saving updated exam with ID: {}", id);
        return examRepository.save(exam);
    }

    public Exam findById(Long id) {
    	log.debug("Finding exam by ID: {}", id);
        return examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
    }

    /** Soft-delete: moves the exam to the trash. */
    public void softDelete(Long id) {
    	log.info("Soft-deleting exam with ID: {}", id);
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
        exam.setDeletedAt(java.time.LocalDateTime.now());
        examRepository.save(exam);
    }

    /** Restore a trashed exam back to the live list. */
    public Exam restore(Long id) {
    	log.info("Restoring exam with ID: {}", id);
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
        exam.setDeletedAt(null);
        return examRepository.save(exam);
    }

    /** Permanently removes the exam from the database. */
    public void deletePermanently(Long id) {
    	log.warn("Permanently deleting exam with ID: {}", id);
        examRepository.deleteById(id);
        log.info("Successfully permanently deleted exam with ID: {}", id);
    }

    public Exam toggleActive(Long id) {
    	log.info("Toggling active status for exam with ID: {}", id);
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
        exam.setActive(!exam.isActive());
        log.info("Successfully toggled active status for exam ID {}", id);
        return examRepository.save(exam);
    }
}
