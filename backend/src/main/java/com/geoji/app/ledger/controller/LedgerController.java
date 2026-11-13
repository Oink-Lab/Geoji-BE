package com.geoji.app.ledger.controller;

import com.geoji.app.global.ApiException;
import com.geoji.app.global.ApiResponse;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.service.LedgerService;
import com.geoji.app.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class LedgerController {
    private final LedgerService service;

    LedgerController(LedgerService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<LedgerService.View>> create(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody LedgerService.Create input
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(user, input)));
    }

    @GetMapping("/{id}")
    ApiResponse<LedgerService.View> get(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.get(user, id));
    }

    @PatchMapping("/{id}")
    ApiResponse<LedgerService.View> update(
        @AuthenticationPrincipal User user,
        @PathVariable UUID id,
        @Valid @RequestBody LedgerService.Update input
    ) {
        return ApiResponse.ok(service.update(user, id, input));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(
        @AuthenticationPrincipal User user, @PathVariable UUID id
    ) {
        service.delete(user, id);
        return ApiResponse.ok(null);
    }

    @GetMapping
    ApiResponse<Page<LedgerService.View>> list(
        @AuthenticationPrincipal User user,
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false) YearMonth month,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 범위가 올바르지 않습니다."
            );
        }
        Sort sort = Sort.by(Sort.Direction.DESC, "transactionDate")
            .and(Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(service.list(
            user, date, month, PageRequest.of(page, size, sort)
        ));
    }

    @GetMapping("/summary")
    ApiResponse<LedgerService.Summary> summary(
        @AuthenticationPrincipal User user, @RequestParam YearMonth month
    ) {
        return ApiResponse.ok(service.summary(user, month));
    }

    @GetMapping("/calendar")
    ApiResponse<LedgerService.CalendarView> calendar(
        @AuthenticationPrincipal User user, @RequestParam YearMonth month
    ) {
        return ApiResponse.ok(service.calendar(user, month));
    }

    @GetMapping("/categories")
    ApiResponse<List<CategoryView>> categories(
        @RequestParam(required = false) LedgerTypes.Type type
    ) {
        List<CategoryView> categories = Arrays.stream(LedgerTypes.Category.values())
            .filter(category -> type == null || category.type == type)
            .map(category -> new CategoryView(
                category.name(), category.label, category.type
            )).toList();
        return ApiResponse.ok(categories);
    }

    @GetMapping("/methods")
    ApiResponse<List<MethodView>> methods() {
        List<String> labels = List.of(
            "신용카드", "체크카드", "현금", "계좌이체", "간편결제", "기타"
        );
        List<MethodView> methods = Arrays.stream(LedgerTypes.Method.values())
            .map(method -> new MethodView(
                method.name(), labels.get(method.ordinal())
            )).toList();
        return ApiResponse.ok(methods);
    }

    public record CategoryView(String code, String name, LedgerTypes.Type type) {}
    public record MethodView(String code, String name) {}
}
