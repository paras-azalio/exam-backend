package com.exam.backend.controller;

import com.exam.backend.repository.ExamResultRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminResultController {

    private final ExamResultRepository examResultRepository;

    @Value("${storage.base-path:C:/exam-recordings}")
    private String basePath;

    // ─────────────────────────────────────────────────────────────────────────
    // Check / flag
    // ─────────────────────────────────────────────────────────────────────────

    @PatchMapping("/results/{id}/check")
    public ResponseEntity<Void> updateCheck(@PathVariable Long id,
                                            @RequestBody Map<String, Boolean> body) {
        var opt = examResultRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        var result = opt.get();
        result.setChecked(Boolean.TRUE.equals(body.get("checked")));
        examResultRepository.save(result);
        return ResponseEntity.ok().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recordings — list
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the list of recorded files for a given result:
     *   { sessionKey, html, camera: [...], screen: [...] }
     */
    @GetMapping("/results/{resultId}/recordings")
    public ResponseEntity<?> listRecordings(@PathVariable Long resultId) {
        var opt = examResultRepository.findById(resultId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        String sessionKey = opt.get().getSessionKey();
        Path   sessionDir = Paths.get(basePath, sessionKey).normalize();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionKey", sessionKey);

        if (!Files.exists(sessionDir)) {
            resp.put("html",   null);
            resp.put("camera", List.of());
            resp.put("screen", List.of());
            return ResponseEntity.ok(resp);
        }

        Path htmlPath = sessionDir.resolve(sessionKey + ".html");
        resp.put("html",   Files.exists(htmlPath) ? sessionKey + ".html" : null);
        resp.put("camera", listChunks(sessionDir.resolve("camera")));
        resp.put("screen", listChunks(sessionDir.resolve("screen")));

        return ResponseEntity.ok(resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recordings — serve file (with HTTP Range support for video seeking)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Streams a recording file.
     *
     * filePath examples:
     *   camera/camera_chunk_0001.webm
     *   screen/screen_chunk_0003.webm
     *   {sessionKey}.html
     */
    @GetMapping("/recordings/file")
    public void serveFile(@RequestParam String sessionKey,
                          @RequestParam String filePath,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {

        // Prevent path traversal
        Path base = Paths.get(basePath).normalize();
        Path file = base.resolve(sessionKey).resolve(filePath).normalize();
        if (!file.startsWith(base)) { response.sendError(403, "Forbidden"); return; }
        if (!Files.exists(file) || Files.isDirectory(file)) { response.sendError(404, "Not found"); return; }

        String name = file.getFileName().toString();
        String ct   = name.endsWith(".webm") ? "video/webm"
                    : name.endsWith(".html") ? "text/html; charset=UTF-8"
                    : "application/octet-stream";

        long   size        = Files.size(file);
        String rangeHeader = request.getHeader("Range");

        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType(ct);
        response.setHeader("Content-Disposition", "inline; filename=\"" + name + "\"");
        // Allow CORS for blob-URL fetch from the admin SPA
        response.setHeader("Access-Control-Allow-Origin",  "*");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Range");
        response.setHeader("Access-Control-Expose-Headers","Content-Range, Accept-Ranges, Content-Length");

        if (rangeHeader == null || rangeHeader.isBlank()) {
            response.setContentLengthLong(size);
            response.setStatus(200);
            Files.copy(file, response.getOutputStream());
        } else {
            String[] parts = rangeHeader.substring("bytes=".length()).split("-");
            long start = Long.parseLong(parts[0].trim());
            long end   = (parts.length > 1 && !parts[1].trim().isEmpty())
                         ? Long.parseLong(parts[1].trim()) : size - 1;
            end = Math.min(end, size - 1);
            long len = end - start + 1;

            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            response.setContentLengthLong(len);

            try (var in = Files.newInputStream(file)) {
                in.skipNBytes(start);
                byte[] buf  = new byte[65_536];
                long   left = len;
                int    read;
                while (left > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, left))) != -1) {
                    response.getOutputStream().write(buf, 0, read);
                    left -= read;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recordings — delete entire session folder
    // ─────────────────────────────────────────────────────────────────────────

    @DeleteMapping("/results/{resultId}/folder")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long resultId) {
        var opt = examResultRepository.findById(resultId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Path base = Paths.get(basePath).normalize();
        Path dir  = base.resolve(opt.get().getSessionKey()).normalize();
        if (!dir.startsWith(base)) return ResponseEntity.status(403).build();

        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> listChunks(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".webm"))
                         .map(p -> p.getFileName().toString())
                         .sorted()                          // ascending → play in order
                         .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }
}
