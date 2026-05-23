package com.exam.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Generates signed exam-invite JWT links (HS256, no external library needed).
 *
 * Token payload:
 *   { "sub": "email", "name": "Full Name", "examCode": "CODE",
 *     "iat": epoch, "exp": epoch [, "nbf": epoch] }
 *
 * Optional nbf (not-before) claim is included when a validFromIso is supplied,
 * allowing tokens that are generated today but only become usable at a future datetime.
 *
 * The frontend simply base64url-decodes the middle segment to read the payload.
 * No verification is needed on the frontend — the server is the only token producer.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final ObjectMapper mapper;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a signed invite link.
     *
     * @param validForMinutes  fallback duration used when validUntilIso is null/blank
     * @param validFromIso     ISO-8601 UTC string for the nbf claim; null → valid immediately
     * @param validUntilIso    ISO-8601 UTC string for the exp claim; null → now + validForMinutes
     */
    public String generateLink(String userName, String userEmail, String examCode,
                               int validForMinutes, String validFromIso, String validUntilIso) {
        String token = buildToken(userName, userEmail, examCode, validForMinutes, validFromIso, validUntilIso);
        return frontendUrl.replaceAll("/$", "") + "/?usr=" + token;
    }

    /** Human-readable expiry label for the response. */
    public String computeExpiresAt(int validForMinutes, String validUntilIso) {
        if (validUntilIso != null && !validUntilIso.isBlank()) {
            return FMT.format(Instant.parse(validUntilIso));
        }
        return FMT.format(Instant.now().plusSeconds((long) validForMinutes * 60));
    }

    /**
     * Validates an HS256 JWT (signature + expiry + nbf) and returns its decoded payload.
     * Throws {@link IllegalArgumentException} if the token is invalid, expired, or not yet active.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyAndExtract(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid token format");

        // Verify signature
        String data     = parts[0] + "." + parts[1];
        String expected = sign(data);
        if (!expected.equals(parts[2])) throw new IllegalArgumentException("Invalid token signature");

        // Decode payload
        String json;
        try {
            json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid token encoding");
        }

        Map<String, Object> payload;
        try {
            payload = mapper.readValue(json, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid token payload");
        }

        long now = Instant.now().getEpochSecond();

        // Check expiry
        if (payload.containsKey("exp")) {
            long exp = ((Number) payload.get("exp")).longValue();
            if (now > exp) throw new IllegalArgumentException("Token has expired");
        }

        // Check not-before
        if (payload.containsKey("nbf")) {
            long nbf = ((Number) payload.get("nbf")).longValue();
            if (now < nbf) throw new IllegalArgumentException("Token is not yet active");
        }

        return payload;
    }

    /** Human-readable valid-from label, or null when the token is valid immediately. */
    public String computeValidFrom(String validFromIso) {
        if (validFromIso == null || validFromIso.isBlank()) return null;
        return FMT.format(Instant.parse(validFromIso));
    }

    // ── JWT construction ──────────────────────────────────────────────────────

    private String buildToken(String userName, String userEmail, String examCode,
                              int validForMinutes, String validFromIso, String validUntilIso) {
        long   now = Instant.now().getEpochSecond();
        String jti = UUID.randomUUID().toString();

        // exp: use exact window if provided, otherwise fall back to duration
        long exp = (validUntilIso != null && !validUntilIso.isBlank())
                ? Instant.parse(validUntilIso).getEpochSecond()
                : now + ((long) validForMinutes * 60);

        // nbf: include only when a future valid-from is specified
        Long nbf = (validFromIso != null && !validFromIso.isBlank())
                ? Instant.parse(validFromIso).getEpochSecond()
                : null;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "{\"jti\":\"%s\",\"sub\":\"%s\",\"name\":\"%s\",\"examCode\":\"%s\",\"iat\":%d,\"exp\":%d",
            jti,
            escapeJson(userEmail),
            escapeJson(userName),
            escapeJson(examCode),
            now, exp
        ));
        if (nbf != null) {
            sb.append(",\"nbf\":").append(nbf);
        }
        sb.append("}");

        String header  = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64(sb.toString());
        String data    = header + "." + payload;
        return data + "." + sign(data);
    }

    private String b64(String input) {
        return Base64.getUrlEncoder().withoutPadding()
                     .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                         .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("JWT signing failed", e);
        }
    }

    /** Minimal JSON string escaping for name/email/examCode values. */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
