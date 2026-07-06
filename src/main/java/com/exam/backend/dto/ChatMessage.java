package com.exam.backend.dto;

import lombok.Data;

/**
 * A real-time text chat message exchanged between an HR Admin and a Candidate
 * during a live-proctored exam. Routed by sessionKey, exactly like signaling:
 *
 *   sender = "candidate" → /topic/chat/admin/{sessionKey}
 *   sender = "admin"     → /topic/chat/candidate/{sessionKey}
 */
@Data
public class ChatMessage {
    private String sessionKey;
    private String sender;   // "admin" | "candidate"
    private String name;     // display name of the sender
    private String text;     // message body
    private long   ts;        // epoch millis; backend fills it in if 0
}
