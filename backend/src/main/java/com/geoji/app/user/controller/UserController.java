package com.geoji.app.user.controller;

import com.geoji.app.global.ApiResponse;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    public record View(UUID id, String email, String nickname, Instant createdAt) {
        public static View of(User user) {
            return new View(user.getId(), user.getEmail(), user.getNickname(), user.getCreatedAt());
        }
    }

    public record Update(@NotBlank @Size(max = 50) String nickname) {}

    public record ChangePassword(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 72) String newPassword
    ) {}

    @GetMapping
    ApiResponse<View> me(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(View.of(user));
    }

    @PatchMapping
    ApiResponse<View> update(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody Update input
    ) {
        return ApiResponse.ok(View.of(service.updateNickname(user, input.nickname())));
    }

    @PatchMapping("/password")
    ApiResponse<Void> password(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody ChangePassword input
    ) {
        service.changePassword(user, input.currentPassword(), input.newPassword());
        return ApiResponse.ok(null);
    }

    @DeleteMapping
    ApiResponse<Void> withdraw(@AuthenticationPrincipal User user) {
        service.withdraw(user);
        return ApiResponse.ok(null);
    }
}
