package com.exam.backend.service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.exam.backend.dto.ChatMessage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ChatHistoryRegistry {

    private final ConcurrentHashMap<String, List<ChatMessage>> history = new ConcurrentHashMap<>();

    public void add(ChatMessage msg) {
        if (msg.getSessionKey() == null || msg.getSessionKey().isBlank()) return;
        history.computeIfAbsent(msg.getSessionKey(), k -> new CopyOnWriteArrayList<>()).add(msg);
    }

    public List<ChatMessage> get(String sessionKey) {
        return history.getOrDefault(sessionKey, List.of());
    }

    public void clear(String sessionKey) {
        if (sessionKey == null) return;
        List<ChatMessage> removed = history.remove(sessionKey);
        if (removed != null) {
            log.info("Cleared chat history for sessionKey={} ({} messages)", sessionKey, removed.size());
        }
    }
}