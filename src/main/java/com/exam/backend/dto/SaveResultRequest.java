package com.exam.backend.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SaveResultRequest {
    private String sessionKey;
    private String studentName;
    private String studentEmail;
    /** jti claim from the JWT invite token; present only for link-based exam entry. */
    private String jti;
    private String examCode;
    private String examTitle;
    /** ISO-8601 timestamp of when the student began the exam (sent from frontend). */
    private String startedAt;

    /**
     * Raw student answers — [{questionId: string, answer: string | string[]}].
     * Scoring is performed server-side; the frontend never sends a pre-computed score.
     */
    private List<Map<String, Object>> answers;

    /**
     * Maps questionId → display number so the HTML report preserves the same
     * question ordering the student saw (which may differ from the JSON order
     * when questions or options are shuffled).
     */
    private Map<String, Object> questionOrderMap;

    /** Number of tab-switch / focus-loss violations recorded on the frontend. */
    private Integer violations;

    // --- GAZE TRACKING START ---
    /** Client-side gaze / face tracking events captured during the exam. */
    private List<Map<String, Object>> gazeEvents;
    // --- GAZE TRACKING END ---
}
