package com.exam.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Service
public class ExamService {

    @Value("${exam.data-path:exams/}")
    private String examDataPath;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Loads exam JSON from classpath:exams/{examCode}.json
     * Returns the raw Map so the frontend receives the same structure it already knows.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExam(String examCode) throws IOException {
        String path = examDataPath + examCode.toUpperCase() + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            return mapper.readValue(is, Map.class);
        }
    }
}
