package com.exam.backend.controller;

import com.exam.backend.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

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

        if (sessionKey == null || sessionKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionKey required"));
        }
        try {
            String savedPath = mediaService.saveChunk(sessionKey, source, chunkIndex, file);
            return ResponseEntity.ok(Map.of(
                    "status", "saved",
                    "path", savedPath,
                    "chunkIndex", chunkIndex
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save chunk: " + e.getMessage()));
        }
    }
}
