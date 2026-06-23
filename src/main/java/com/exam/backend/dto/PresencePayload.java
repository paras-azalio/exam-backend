package com.exam.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Presence record for one candidate currently taking an exam.
 *
 * Sent by the candidate on JOIN (/app/presence/join) and stored in the
 * in-memory roster. The full roster is broadcast to admins on /topic/presence.
 *
 * status values used by the frontend: "active" | "submitting" | "left".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PresencePayload {
    private String sessionKey;   // unique per exam attempt — also the WebRTC routing id
    private String name;         // candidate display name
    private String examCode;     // which exam they are taking
    private String status;       // "active" | "submitting" | "left"
}
