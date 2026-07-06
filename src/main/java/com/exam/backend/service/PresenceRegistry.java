package com.exam.backend.service;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.exam.backend.dto.PresencePayload;

import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe in-memory registry of candidates who are currently taking an exam
 * with live proctoring enabled.
 *
 * Keyed by exam sessionKey. A second map links the transient WebSocket session id
 * to the exam sessionKey so a candidate can be removed automatically when their
 * socket disconnects (tab closed / network drop) without an explicit LEAVE.
 *
 * This is process-local. If the backend is ever scaled to multiple instances,
 * swap this for a shared store (e.g. Redis) and an external STOMP relay.
 */
@Slf4j
@Component
public class PresenceRegistry {

    /** sessionKey → presence record. */
    private final ConcurrentHashMap<String, PresencePayload> roster = new ConcurrentHashMap<>();

    /** websocket session id → exam sessionKey (for disconnect cleanup). */
    private final ConcurrentHashMap<String, String> socketToSession = new ConcurrentHashMap<>();

    /** Adds or updates a candidate and links the owning WebSocket session. */
    public void join(PresencePayload payload, String wsSessionId) {
        if (payload.getSessionKey() == null || payload.getSessionKey().isBlank()) return;
        if (payload.getStatus() == null || payload.getStatus().isBlank()) {
            payload.setStatus("active");
        }
        roster.put(payload.getSessionKey(), payload);
        if (wsSessionId != null) socketToSession.put(wsSessionId, payload.getSessionKey());
        log.info("Presence JOIN: sessionKey={} name={} examCode={} (roster size={})",
                payload.getSessionKey(), payload.getName(), payload.getExamCode(), roster.size());
    }

    /** Removes a candidate by sessionKey. */
    public void leave(String sessionKey) {
        if (sessionKey == null) return;
        PresencePayload removed = roster.remove(sessionKey);
        socketToSession.values().removeIf(sessionKey::equals);
        if (removed != null) {
            log.info("Presence LEAVE: sessionKey={} (roster size={})", sessionKey, roster.size());
        }
    }

    /** Removes whatever candidate was bound to a now-closed WebSocket session. */
    public String removeBySocket(String wsSessionId) {
        if (wsSessionId == null) return null;
        String sessionKey = socketToSession.remove(wsSessionId);
        if (sessionKey != null) {
            roster.remove(sessionKey);
            log.info("Presence DISCONNECT: ws={} sessionKey={} (roster size={})",
                    wsSessionId, sessionKey, roster.size());
        }
        return sessionKey;
    }

    /** Current snapshot of all active candidates. */
    public Collection<PresencePayload> roster() {
        return roster.values();
    }
}
