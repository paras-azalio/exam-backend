package com.exam.backend.dto;

import com.exam.backend.model.ExamResult;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Returned by ResultService.saveResult() — carries both the persisted row and
 *  the full question-level detail list needed by the API response + PDF report. */
@Data
@AllArgsConstructor
public class ScoredResult {
    private ExamResult result;
    private double score;
    private double totalMarks;
    private String grade;
    private List<Map<String, Object>> details;
}
