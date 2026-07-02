package com.exam.backend.dto;

import lombok.Data;

@Data
public class GenerateLinkRequest {
    private String userName;
    private String userEmail;
    /** How long the link stays valid, in minutes (used when validFromIso / validUntilIso are absent). */
    private int validForMinutes;
    /** ISO-8601 UTC datetime for the earliest moment the token is valid (nbf claim). Nullable. */
    private String validFromIso;
    /** ISO-8601 UTC datetime for the expiry (exp claim). Overrides validForMinutes when present. Nullable. */
    private String validUntilIso;

    /**
     * When true, the generated JWT will include a "liveStream": true claim.
     * The candidate frontend opens a WebSocket on exam start and streams its
     * camera + screen to HR Admins in real time via WebRTC. Independent of
     * sebRequired — live proctoring works with or without Safe Exam Browser.
     */
    private boolean liveStream;
}
