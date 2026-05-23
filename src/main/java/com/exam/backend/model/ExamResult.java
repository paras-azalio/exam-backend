package com.exam.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_results")
@Data
@NoArgsConstructor
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", nullable = false, length = 300)
    private String sessionKey;

    @Column(name = "student_name")
    private String studentName;

    @Column(name = "student_email")
    private String studentEmail;

    @Column(name = "exam_code", length = 50)
    private String examCode;

    @Column(name = "exam_title")
    private String examTitle;

    @Column(name = "score")
    private Double score;

    @Column(name = "total_marks")
    private Double totalMarks;

    @Column(name = "grade", length = 10)
    private String grade;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    /** When the student actually started the exam (sent from the frontend). */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** When the result was submitted / persisted. */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Admin-flagged (checked) entries are moved to the bottom of the results list. */
    @Column(name = "checked", nullable = false)
    private boolean checked = false;

    /**
     * jti (JWT ID) from the student's invite token.
     * Used to identify this result row when the async verbal-evaluation
     * webhook calls back from FastAPI.
     */
    @Column(name = "jti", length = 100)
    private String jti;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
