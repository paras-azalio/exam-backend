package com.exam.backend.repository;

import com.exam.backend.model.Exam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    Optional<Exam> findByExamCodeIgnoreCaseAndActiveTrue(String examCode);
    boolean existsByExamCodeIgnoreCase(String examCode);
    boolean existsByExamCodeIgnoreCaseAndIdNot(String examCode, Long id);
    List<Exam> findAllByOrderByCreatedAtDesc();
}
