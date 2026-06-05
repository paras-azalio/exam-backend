package com.exam.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class MediaService {

    @Value("${storage.base-path:C:/exam-recordings}")
    private String basePath;

    public String saveChunk(String sessionKey, String source, int chunkIndex, MultipartFile file)
            throws IOException {
    	log.debug("Attempting to save {} chunk index {} for sessionKey: {}", source, chunkIndex, sessionKey);
        Path dir = Paths.get(basePath, sessionKey, source);
        Files.createDirectories(dir);

        String filename = source + "_chunk_" + String.format("%04d", chunkIndex) + ".webm";
        Path dest = dir.resolve(filename);
        file.transferTo(dest);
        log.debug("Successfully saved chunk to path: {}", dest);
        return dest.toString();
    }
}
