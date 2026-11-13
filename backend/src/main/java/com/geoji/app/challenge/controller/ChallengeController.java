package com.geoji.app.challenge.controller;

import com.geoji.app.challenge.dto.ChallengeDtos;
import com.geoji.app.challenge.service.ChallengeService;
import com.geoji.app.global.ApiResponse;
import com.geoji.app.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/challenges")
public class ChallengeController {
    private final ChallengeService service;

    public ChallengeController(ChallengeService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<ChallengeDtos.View>> create(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody ChallengeDtos.Create input
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(user, input)));
    }

    @GetMapping("/current")
    ApiResponse<ChallengeDtos.View> current(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(service.current(user));
    }

    @GetMapping("/history")
    ApiResponse<List<ChallengeDtos.View>> history(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(service.history(user));
    }

    @GetMapping("/{id}")
    ApiResponse<ChallengeDtos.View> get(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.get(user, id));
    }

    @GetMapping("/{id}/progress")
    ApiResponse<ChallengeDtos.Progress> progress(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.progress(user, id));
    }

    @GetMapping("/{id}/calendar")
    ApiResponse<ChallengeDtos.Calendar> calendar(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.calendar(user, id));
    }

    @GetMapping("/{id}/result")
    ApiResponse<ChallengeDtos.Result> result(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.result(user, id));
    }

    @GetMapping("/{id}/retry")
    ApiResponse<ChallengeDtos.Retry> retry(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.retry(user, id));
    }

    @PostMapping("/{id}/abandon")
    ApiResponse<ChallengeDtos.View> abandon(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.abandon(user, id));
    }

    @PostMapping("/{id}/cancel")
    ApiResponse<ChallengeDtos.View> cancel(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.cancel(user, id));
    }
}
