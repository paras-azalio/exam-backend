package com.exam.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Records every JWT invite token that has been consumed by a submitted exam.
 * The primary key is the JWT's jti claim (a UUID), so uniqueness is enforced at the DB level.
 */
@Entity
@Table(name = "used_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsedToken {

    /** jti claim from the JWT — UUID string. */
    @Id
    @Column(name = "jti", length = 100)
    private String jti;

    @Column(name = "exam_code", length = 50)
    private String examCode;

    @Column(name = "student_email")
    private String studentEmail;

    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
