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
    private Double score;
    private Double totalMarks;
    private String grade;
    private List<Map<String, Object>> details;
    /** ISO-8601 timestamp of when the student began the exam (sent from frontend). */
    private String startedAt;
}
