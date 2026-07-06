package com.exam.backend.dto;

import java.util.Map;

import lombok.Data;

/**
 * A single WebRTC signaling message relayed between an HR Admin and a Candidate.
 *
 * The backend is intentionally "dumb" about the WebRTC details: it only reads
 * {@code sessionKey} (routing id) and {@code sender} (direction) and forwards the
 * whole object verbatim to the correct topic. The browser clients construct and
 * interpret the inner fields.
 *
 *   sender = "admin"     → forwarded to /topic/candidate/{sessionKey}
 *   sender = "candidate" → forwarded to /topic/admin/{sessionKey}
 *
 * kind values: "offer" | "answer" | "ice-candidate" | "media-state".
 */
@Data
public class SignalMessage {

    /** Candidate's exam sessionKey — the routing identifier for this 1:1 stream. */
    private String sessionKey;

    /** Who sent this message: "admin" or "candidate". Drives the routing direction. */
    private String sender;

    /** "offer" | "answer" | "ice-candidate". */
    private String kind;

    /** RTCSessionDescriptionInit ({type, sdp}) for offer / answer. Null otherwise. */
    private Object sdp;

    /** RTCIceCandidateInit for ice-candidate messages. Null otherwise. */
    private Object candidate;

    /**
     * Optional map of {mediaStreamId → "camera" | "screen"} that the candidate
     * sends alongside its answer so the admin can route each received track to
     * the correct &lt;video&gt; element. Null on admin → candidate messages.
     */
    private Map<String, String> streamMap;

    /**
     * For {@code kind = "media-state"} (admin → candidate only): the admin's
     * current outbound media state, e.g. {@code {"video": true, "audio": false}}.
     * The candidate uses this to deterministically show/hide the floating proctor
     * window instead of inferring it from WebRTC track mute/direction changes.
     */
    private Map<String, Boolean> media;

    /**
     * Admin → candidate: unique id of the admin's current viewing session. The admin
     * creates a fresh peer connection each time the live viewer opens; a changed id
     * tells the candidate to discard its stale peer and build a matching fresh one,
     * so reopening the viewer reliably re-establishes the candidate's media.
     */
    private String session;
}
