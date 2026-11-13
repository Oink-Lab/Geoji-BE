package com.geoji.app.auth.config;

import com.geoji.app.auth.service.TokenService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoji.app.global.ApiResponse;
import com.geoji.app.global.ApiException;
import com.geoji.app.user.entity.User;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain chain(HttpSecurity http, JwtFilter jwtFilter, ObjectMapper json) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/oauth/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> write(json, res, 401, "UNAUTHORIZED", "인증이 필요합니다."))
                .accessDeniedHandler((req, res, ex) -> write(json, res, 403, "FORBIDDEN", "접근 권한이 없습니다.")))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean CorsConfigurationSource cors(@Value("${geoji.cors-origins}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
    static void write(ObjectMapper json, HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getWriter(), ApiResponse.fail(code, message));
    }
}

@Component
class JwtFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final ObjectMapper json;
    JwtFilter(TokenService tokens, ObjectMapper json) { this.tokens = tokens; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            if (!header.startsWith("Bearer ") || header.length() <= 7) { SecurityConfig.write(json, response, 401, "INVALID_TOKEN", "인증이 유효하지 않습니다."); return; }
            try {
                User user = tokens.authenticated(header.substring(7));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
            } catch (ApiException e) { SecurityConfig.write(json, response, 401, e.code(), e.getMessage()); return; }
            catch (org.springframework.data.redis.RedisConnectionFailureException e) { SecurityConfig.write(json, response, 503, "AUTH_UNAVAILABLE", "인증 서비스를 사용할 수 없습니다."); return; }
        }
        chain.doFilter(request, response);
    }
}
