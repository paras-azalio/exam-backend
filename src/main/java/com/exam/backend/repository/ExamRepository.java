package com.exam.backend.repository;

import com.exam.backend.model.Exam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    /** Active exams visible to students — excludes anything in the trash. */
    Optional<Exam> findByExamCodeIgnoreCaseAndActiveTrueAndDeletedAtIsNull(String examCode);

    /** All live (non-trashed) exams for the admin list. */
    List<Exam> findByDeletedAtIsNullOrderByCreatedAtDesc();

    /** All soft-deleted exams for the trash view. */
    List<Exam> findByDeletedAtIsNotNullOrderByDeletedAtDesc();

    boolean existsByExamCodeIgnoreCase(String examCode);
    boolean existsByExamCodeIgnoreCaseAndIdNot(String examCode, Long id);
}
