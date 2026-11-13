package com.geoji.app.auth.controller;

import com.geoji.app.auth.service.TokenService;
import com.geoji.app.global.ApiException;
import com.geoji.app.global.ApiResponse;
import com.geoji.app.user.controller.UserController;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final StringRedisTemplate redis;

    AuthController(
        UserRepository users,
        PasswordEncoder passwords,
        TokenService tokens,
        StringRedisTemplate redis
    ) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.redis = redis;
    }

    public record Signup(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 50) String nickname
    ) {}

    public record Login(@NotBlank String email, @NotBlank String password) {}
    public record Refresh(@NotBlank String refreshToken) {}
    public record Logout(String refreshToken) {}

    @PostMapping("/signup")
    ResponseEntity<ApiResponse<UserController.View>> signup(
        @Valid @RequestBody Signup input
    ) {
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCaseAndStatus(email, User.Status.ACTIVE)) {
            throw new ApiException(
                HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 사용 중인 이메일입니다."
            );
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwords.encode(input.password()));
        user.setNickname(input.nickname().trim());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(UserController.View.of(users.save(user))));
    }

    @PostMapping("/login")
    ApiResponse<TokenService.Tokens> login(@Valid @RequestBody Login input) {
        String rateKey = rateKey(input.email());
        Long attempts = redis.opsForValue().increment(rateKey);
        if (attempts != null && attempts == 1) {
            redis.expire(rateKey, Duration.ofMinutes(15));
        }
        if (attempts != null && attempts > 10) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "잠시 후 다시 시도해 주세요."
            );
        }
        User user = users.findByEmailIgnoreCaseAndStatus(
            input.email().trim(), User.Status.ACTIVE
        ).orElseThrow(() -> invalidCredentials());
        if (user.getPasswordHash() == null
            || !passwords.matches(input.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        redis.delete(rateKey);
        return ApiResponse.ok(tokens.issue(user));
    }

    @PostMapping("/refresh")
    ApiResponse<TokenService.Tokens> refresh(@Valid @RequestBody Refresh input) {
        return ApiResponse.ok(tokens.rotate(input.refreshToken()));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(
        @AuthenticationPrincipal User user,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String header,
        @RequestBody(required = false) Logout input
    ) {
        tokens.logout(header.substring(7), input == null ? null : input.refreshToken());
        return ApiResponse.ok(null);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(
            HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
            "이메일 또는 비밀번호가 올바르지 않습니다."
        );
    }

    private static String rateKey(String email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                email.trim().toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8)
            );
            return "auth:login:attempts:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
