package com.exam.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GenerateLinkResponse {
    private String link;
    private String expiresAt;
    /** Human-readable valid-from datetime, or null when the token is valid immediately. */
    private String validFrom;
}
