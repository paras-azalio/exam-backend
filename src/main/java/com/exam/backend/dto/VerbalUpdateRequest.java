package com.exam.backend.dto;

import lombok.Data;

/**
 * Webhook payload sent by FastAPI (or the simulation endpoint) after
 * evaluating ONE verbal answer.  One callback per question.
 *
 * JSON shape:
 * {
 *   "jti":          "550e8400-e29b-41d4-a716-446655440000",
 *   "questionId":   "q4",
 *   "questionText": "Briefly introduce yourself and describe your experience.",
 *   "score":        "7.5",
 *   "precision":    3,
 *   "maxMarks":     10,
 *   "secret":       "QuickScreenVerbal#2026"
 * }
 *
 * NOTE: questionId is the preferred lookup key. If absent, falls back to questionText.
 */
@Data
public class VerbalUpdateRequest {
    private String jti;
    /** Question ID from the exam JSON (e.g. "q4") — preferred lookup key. */
    private String questionId;
    /** Full question text — fallback lookup key if questionId is absent. */
    private String questionText;
    /** AI-assigned score as string (e.g. "7.5") */
    private String score;
    /** Precision level used for evaluation (1-5) */
    private int    precision;
    /** Maximum marks for this verbal question. */
    private double maxMarks;
    private String secret;
}
