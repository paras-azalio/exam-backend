package com.exam.backend.repository;

import com.exam.backend.model.AiResult;
import com.exam.backend.model.ExamResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiResultRepository extends JpaRepository<AiResult, Long> {

    /** All verbal evaluations for a given exam submission. */
    List<AiResult> findByExamResult(ExamResult examResult);

    /** Primary webhook lookup: jti + questionId (preferred). */
    Optional<AiResult> findByJtiAndQuestionId(String jti, String questionId);

    /** Fallback webhook lookup: jti + question text. */
    Optional<AiResult> findByJtiAndQuestion(String jti, String question);

    /** All evaluations for a jti (used for status checks). */
    List<AiResult> findByJti(String jti);

    /**
     * Loads an AiResult together with its parent ExamResult in one query.
     * Used by fireOne() which runs on a pool thread with no active JPA session —
     * JOIN FETCH avoids LazyInitializationException when accessing examResult.sessionKey.
     */
    @Query("SELECT a FROM AiResult a JOIN FETCH a.examResult WHERE a.id = :id")
    Optional<AiResult> findByIdEager(@Param("id") Long id);
}
