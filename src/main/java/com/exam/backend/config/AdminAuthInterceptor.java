package com.exam.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Base64;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:azaLio@2002-18-06}")
    private String adminPassword;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6)));
                String[] parts = decoded.split(":", 2);
                if (parts.length == 2
                        && adminUsername.equals(parts[0])
                        && adminPassword.equals(parts[1])) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"QuickScreen Admin\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
        return false;
    }
}
