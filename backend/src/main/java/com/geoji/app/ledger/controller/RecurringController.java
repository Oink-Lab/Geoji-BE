package com.geoji.app.ledger.controller;

import com.geoji.app.global.ApiResponse;
import com.geoji.app.ledger.service.RecurringService;
import com.geoji.app.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recurring-expenses")
public class RecurringController {
    private final RecurringService service;

    RecurringController(RecurringService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<RecurringService.View>> create(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody RecurringService.Create input
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(user, input)));
    }

    @GetMapping
    ApiResponse<List<RecurringService.View>> list(
        @AuthenticationPrincipal User user
    ) {
        return ApiResponse.ok(service.list(user));
    }

    @GetMapping("/schedule")
    ApiResponse<List<RecurringService.ScheduledView>> schedule(
        @AuthenticationPrincipal User user, @RequestParam YearMonth month
    ) {
        return ApiResponse.ok(service.schedule(user, month));
    }

    @GetMapping("/{id}")
    ApiResponse<RecurringService.View> get(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.get(user, id));
    }

    @PatchMapping("/{id}")
    ApiResponse<RecurringService.View> update(
        @AuthenticationPrincipal User user,
        @PathVariable UUID id,
        @Valid @RequestBody RecurringService.Update input
    ) {
        return ApiResponse.ok(service.update(user, id, input));
    }

    @PatchMapping("/{id}/pause")
    ApiResponse<RecurringService.View> pause(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.pause(user, id));
    }

    @PatchMapping("/{id}/resume")
    ApiResponse<RecurringService.View> resume(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.resume(user, id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> cancel(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        service.cancel(user, id);
        return ApiResponse.ok(null);
    }
}
