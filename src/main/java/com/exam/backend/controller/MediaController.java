package com.exam.backend.controller;

import com.exam.backend.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    /**
     * Receives a video/audio chunk from the frontend.
     *
     * Form fields:
     *   file        – the WebM blob
     *   sessionKey  – unique session identifier
     *   source      – "camera" or "screen"
     *   chunkIndex  – sequential number (1-based)
     */
    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionKey") String sessionKey,
            @RequestParam("source") String source,
            @RequestParam("chunkIndex") int chunkIndex) {
    	log.info("Received request to upload {} chunk {} for sessionKey: {}", source, chunkIndex, sessionKey);

        if (sessionKey == null || sessionKey.isBlank()) {
        	log.warn("Upload rejected: sessionKey is missing or blank");
            return ResponseEntity.badRequest().body(Map.of("error", "sessionKey required"));
        }
        try {
            String savedPath = mediaService.saveChunk(sessionKey, source, chunkIndex, file);
            log.debug("Successfully saved {} chunk {} for sessionKey: {} at path: {}", source, chunkIndex, sessionKey, savedPath);
            return ResponseEntity.ok(Map.of(
                    "status", "saved",
                    "path", savedPath,
                    "chunkIndex", chunkIndex
            ));
        } catch (IOException e) {
        	log.error("Failed to save {} chunk {} for sessionKey: {}", source, chunkIndex, sessionKey, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save chunk: " + e.getMessage()));
        }
    }
}
