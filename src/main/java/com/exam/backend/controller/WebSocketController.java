package com.exam.backend.controller;


import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.exam.backend.dto.ChatMessage;
import com.exam.backend.dto.PresencePayload;
import com.exam.backend.dto.SignalMessage;
import com.exam.backend.service.PresenceRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.exam.backend.service.ChatHistoryRegistry;

/**
 * Handles the two responsibilities of the live-proctoring socket:
 *
 *   1. Presence  — track which candidates are live and broadcast the roster to admins.
 *   2. Signaling — relay WebRTC offer / answer / ice-candidate between one admin
 *                  and one candidate, routed by the candidate's sessionKey.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final SimpMessagingTemplate messaging;
    private final PresenceRegistry      registry;
    private final ChatHistoryRegistry chatHistory;

    // ── Presence ────────────────────────────────────────────────────────────────

    /** Candidate announces it is taking the exam. Stored, then full roster re-broadcast. */
    @MessageMapping("/presence/join")
    public void join(@Payload PresencePayload payload, SimpMessageHeaderAccessor accessor) {
        registry.join(payload, accessor.getSessionId());
        broadcastRoster();
    }

    /** Candidate explicitly leaves (e.g. on submit). Disconnect also triggers cleanup. */
    @MessageMapping("/presence/leave")
    public void leave(@Payload PresencePayload payload) {
        registry.leave(payload.getSessionKey());
        chatHistory.clear(payload.getSessionKey());   // ← added
        broadcastRoster();
    }

    /** Admin requests the current roster right after subscribing. */
    @MessageMapping("/presence/sync")
    public void sync() {
        broadcastRoster();
    }

    /** Push the full active-candidate list to every subscribed admin. */
    private void broadcastRoster() {
        List<PresencePayload> snapshot = new ArrayList<>(registry.roster());
        messaging.convertAndSend("/topic/presence", snapshot);
        log.debug("Broadcast roster to /topic/presence ({} candidates)", snapshot.size());
    }

    // ── WebRTC signaling ──────────────────────────────────────────────────────────

    /**
     * Relays a signaling message to the correct counterpart.
     *
     *   sender = "admin"     → /topic/candidate/{sessionKey}  (offer + admin ICE)
     *   sender = "candidate" → /topic/admin/{sessionKey}      (answer + candidate ICE)
     */
    @MessageMapping("/signal")
    public void signal(@Payload SignalMessage msg) {
        String sessionKey = msg.getSessionKey();
        if (sessionKey == null || sessionKey.isBlank() || msg.getSender() == null) {
            log.warn("Dropping signaling message with missing sessionKey/sender: {}", msg.getKind());
            return;
        }

        String destination = "candidate".equalsIgnoreCase(msg.getSender())
                ? "/topic/admin/"     + sessionKey   // candidate → admin
                : "/topic/candidate/" + sessionKey;  // admin → candidate

        messaging.convertAndSend(destination, msg);
        log.debug("Relayed '{}' from {} → {}", msg.getKind(), msg.getSender(), destination);
    }

    // ── Text chat ─────────────────────────────────────────────────────────────────

    /**
     * Relays a chat message to the counterpart, routed by sessionKey.
     *
     *   sender = "candidate" → /topic/chat/admin/{sessionKey}
     *   sender = "admin"     → /topic/chat/candidate/{sessionKey}
     */
    @MessageMapping("/chat")
    public void chat(@Payload ChatMessage msg) {
        String sessionKey = msg.getSessionKey();
        if (sessionKey == null || sessionKey.isBlank() || msg.getSender() == null) {
            log.warn("Dropping chat message with missing sessionKey/sender");
            return;
        }
        if (msg.getTs() == 0L) {
            msg.setTs(System.currentTimeMillis());
        }
        chatHistory.add(msg); 

        String destination = "candidate".equalsIgnoreCase(msg.getSender())
                ? "/topic/chat/admin/"     + sessionKey   // candidate → admin
                : "/topic/chat/candidate/" + sessionKey;  // admin → candidate

        messaging.convertAndSend(destination, msg);
        log.debug("Relayed chat from {} → {}", msg.getSender(), destination);
    }
    
    @MessageMapping("/chat/history")
    public void chatHistory(@Payload ChatMessage request) {
        String sessionKey = request.getSessionKey();
        if (sessionKey == null || sessionKey.isBlank()) return;
        messaging.convertAndSend("/topic/chat/history/" + sessionKey, chatHistory.get(sessionKey));
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    /** Remove a candidate from the roster when their WebSocket closes. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String wsSessionId = event.getSessionId();
        String removed = registry.removeBySocket(wsSessionId);
        if (removed != null) {
        	chatHistory.clear(removed);
            broadcastRoster();
        }
    }
    
    
}
