package com.exam.backend.dto;

import lombok.Data;

@Data
public class ExamRequest {
    private String examData;  // full exam JSON string
    private boolean active = true;
}
