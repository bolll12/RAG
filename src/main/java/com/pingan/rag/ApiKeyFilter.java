package com.pingan.rag;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
final class ApiKeyFilter extends OncePerRequestFilter {
    private final RagSettings settings;
    ApiKeyFilter(RagSettings settings) { this.settings = settings; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        boolean secured = path.equals("/documents") || path.startsWith("/documents/")
                || path.equals("/search") || path.equals("/ask") || path.startsWith("/traces/")
                || path.equals("/api/wiki") || path.startsWith("/api/wiki/");
        if (secured && !settings.apiKey().isEmpty()) {
            String header = request.getHeader("Authorization");
            String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : "";
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), settings.apiKey().getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401); response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(Json.write(Json.map("detail", "无效的访问密钥")));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
