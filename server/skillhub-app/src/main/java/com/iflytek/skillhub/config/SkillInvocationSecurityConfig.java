package com.iflytek.skillhub.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** This one endpoint accepts a service key only. Sessions/Bearer tokens cannot bypass it. */
@Configuration
public class SkillInvocationSecurityConfig {
    public static final String PATH="/api/internal/v1/skill-invocations/batch";
    @Bean
    @Order(-10)
    SecurityFilterChain skillInvocationChain(HttpSecurity http,
            @Value("${skillhub.skill-invocations.key-sha256:}") String configuredHash) throws Exception {
        if (!configuredHash.isBlank() && !configuredHash.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("skill invocation key hash must be SHA-256 hex");
        byte[] expected = configuredHash.isBlank() ? new byte[0] : HexFormat.of().parseHex(configuredHash);
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                String key=request.getHeader("X-Skill-Usage-Key");
                if (expected.length==0 || key==null || key.length()>4096 || !MessageDigest.isEqual(expected,sha256(key))) {
                    response.setStatus(401); response.setContentType("application/json");
                    response.getWriter().write("{\"code\":401,\"msg\":\"Invalid service credential\"}"); return;
                }
                if (request.getContentLengthLong()>2*1024*1024) { response.sendError(413); return; }
                // Also bound chunked bodies, before JSON parsing.
                byte[] body=request.getInputStream().readNBytes(2*1024*1024+1);
                if (body.length>2*1024*1024) { response.sendError(413); return; }
                var wrapped=new HttpServletRequestWrapper(request) {
                    @Override public ServletInputStream getInputStream() {
                        var in=new java.io.ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override public int read() { return in.read(); }
                            @Override public boolean isFinished() { return in.available()==0; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                };
                chain.doFilter(wrapped,response);
            }
        };
        return http.securityMatcher(PATH).csrf(c->c.disable()).requestCache(c->c.disable())
                .securityContext(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c->c.requestMatchers(HttpMethod.POST,PATH).permitAll().anyRequest().denyAll())
                .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class).build();
    }
    private static byte[] sha256(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
