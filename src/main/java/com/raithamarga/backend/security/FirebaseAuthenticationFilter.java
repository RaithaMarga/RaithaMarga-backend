package com.raithamarga.backend.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "raithamarga.actor";

    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthenticationFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return (HttpMethod.OPTIONS.matches(request.getMethod())
                || (HttpMethod.GET.matches(request.getMethod()) && "/api/health".equals(request.getRequestURI()))
                || (HttpMethod.POST.matches(request.getMethod()) && "/api/contact".equals(request.getRequestURI())));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            writeUnauthorized(response);
            return;
        }

        FirebaseToken token;
        try {
            token = firebaseAuth.verifyIdToken(authorization.substring(7).trim());
        } catch (FirebaseAuthException | IllegalArgumentException exception) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE,
                new Actor(token.getUid(), token.getEmail(), token.getName()));
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"A valid Firebase ID token is required.\"}");
    }
}
