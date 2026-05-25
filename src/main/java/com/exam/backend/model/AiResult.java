package com.exam.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per verbal question per exam submission.
 *
 * Lifecycle:  PENDING  →  SENT  →  SUCCESS
 *                               ↘  FAILED
 *
 * PENDING   : Row created at submission time; AI call not yet fired.
 * SENT      : HTTP POST fired to AI API; waiting for webhook callback.
 * SUCCESS   : AI called back; aiScore populated.
 * FAILED    : HTTP error or exception while firing; eligible for retry.
 */
@Entity
@Table(name = "ai_result")
@Data
@NoArgsConstructor
public class AiResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Parent result row. Many verbal questions → one exam result. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_result_id", nullable = false)
    private ExamResult examResult;

    /** JWT token ID — used to correlate the webhook callback. */
    @Column(name = "jti", length = 100)
    private String jti;

    /** Question ID from the exam JSON (e.g. "q4"). */
    @Column(name = "question_id", length = 50)
    private String questionId;

    /** Full question text. */
    @Column(name = "question", columnDefinition = "TEXT")
    private String question;

    /** AI evaluation precision level (1–5). */
    @Column(name = "precision_level")
    private Integer precisionLevel;

    /** AI-assigned score. Null until SUCCESS. */
    @Column(name = "ai_score")
    private Double aiScore;

    /** Maximum marks for this question. */
    @Column(name = "max_marks")
    private Double maxMarks;

    /** Expected/model answer sent to the AI. */
    @Column(name = "expected_reply", columnDefinition = "TEXT")
    private String expectedReply;

    /** Relative path to the audio file: {sessionKey}/verbal_{questionId}.webm */
    @Column(name = "audio_path", length = 500)
    private String audioPath;

    /** Full curl command for the AI API call — useful for manual debugging / retry. */
    @Column(name = "request_curl", columnDefinition = "TEXT")
    private String requestCurl;

//    /** Raw HTTP response body from the AI service at the time of the initial POST (acknowledgement or error). */
//    @Column(name = "request_response", columnDefinition = "TEXT")
//    private String requestResponse;

    /** When the HTTP POST was fired (or last retried). */
    @Column(name = "initiated_at")
    private LocalDateTime initiatedAt;

    /** Complete JSON response body from the AI callback (includes score, transcript, feedback). */
    @Column(name = "response", columnDefinition = "TEXT")
    private String response;

    /** Whisper transcript of the candidate's verbal answer. Null until SUCCESS. */
    @Column(name = "transcript", columnDefinition = "TEXT")
    private String transcript;

    /** AI-generated feedback explaining the score. Null until SUCCESS. */
    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    /** When the webhook callback was received and the score was applied. */
    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** PENDING | SENT | SUCCESS | FAILED */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";
}
