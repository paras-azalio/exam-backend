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
     * When true, the generated JWT will include a "requireSeb": true claim.
     * The frontend checks this claim and blocks access if the candidate is not
     * running inside Safe Exam Browser.
     * Only relevant for .seb config generation — normal generate-link ignores this.
     */
    private boolean sebRequired;
}
