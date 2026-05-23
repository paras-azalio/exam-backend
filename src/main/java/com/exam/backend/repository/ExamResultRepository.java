package com.exam.backend.repository;

import com.exam.backend.model.ExamResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    Optional<ExamResult> findBySessionKey(String sessionKey);
    Optional<ExamResult> findByJti(String jti);
    List<ExamResult> findByExamCodeIgnoreCaseOrderByCreatedAtDesc(String examCode);
}
