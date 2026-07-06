package com.exam.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lightweight connectivity + bandwidth probes for the exam portal's pre-exam
 * "Connection Check" and the in-exam live network indicator.
 *
 * All endpoints are unauthenticated (AdminAuthInterceptor only guards
 * /api/admin/**) and cheap. Several path aliases are mapped so the already-
 * deployed frontend reaches a handler regardless of which exact path it was
 * built with.
 *
 * Full URLs (server.servlet.context-path = /QuickScreen):
 *   GET  /QuickScreen/api/health              → 200, latency probe
 *   GET  /QuickScreen/api/speedtest/download  → ~2MB body, download-speed probe
 *   POST /QuickScreen/api/speedtest/upload    → 200, upload-speed probe
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class SpeedTestController {

    /** Default download payload (~2 MB) — large enough to time reliably. */
    private static final int DEFAULT_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DOWNLOAD_BYTES      = 10 * 1024 * 1024;

    /** Latency probe — returns instantly with an empty 200. */
    @GetMapping({"/health", "/ping", "/speedtest/health"})
    public ResponseEntity<Void> health() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /**
     * Download-speed probe — returns a block of incompressible random bytes.
     * Random (not zero-filled) so any gzip layer can't inflate the measured
     * speed. Size can be tuned with ?bytes= (capped at 10 MB).
     */
    @GetMapping({"/speedtest/download", "/download"})
    public ResponseEntity<byte[]> download(
            @RequestParam(value = "bytes", required = false) Integer bytes) {
        int size = bytes == null
                ? DEFAULT_DOWNLOAD_BYTES
                : Math.min(Math.max(bytes, 1), MAX_DOWNLOAD_BYTES);
        byte[] payload = new byte[size];
        ThreadLocalRandom.current().nextBytes(payload);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(payload);
    }

    /**
     * Upload-speed probe — drains and discards the request body, returns 200.
     * Reads the raw stream so it works with any content type or body size.
     */
    @PostMapping({"/speedtest/upload", "/upload"})
    public ResponseEntity<Void> upload(HttpServletRequest request) {
        long total = 0;
        try (InputStream in = request.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) total += n;
        } catch (IOException e) {
            log.warn("Speed-test upload read failed after {} bytes: {}", total, e.getMessage());
        }
        log.debug("Speed-test upload received {} bytes", total);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
