package com.geoji.app.analytics;

import com.geoji.app.global.ApiException;
import com.geoji.app.global.ApiResponse;
import com.geoji.app.user.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final MonthlyAnalyticsService monthly;
    private final ChallengeAnalyticsService challenges;
    private final ChallengeAggregateService aggregate;
    private final InsightService insights;

    public AnalyticsController(MonthlyAnalyticsService monthly, ChallengeAnalyticsService challenges,
                               ChallengeAggregateService aggregate, InsightService insights) {
        this.monthly = monthly; this.challenges = challenges; this.aggregate = aggregate; this.insights = insights;
    }

    private YearMonth month(String value) {
        try { return YearMonth.parse(value); }
        catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MONTH", "month는 YYYY-MM 형식이어야 합니다.");
        }
    }

    @GetMapping("/monthly")
    ApiResponse<Map<String, Object>> monthly(@AuthenticationPrincipal User user, @RequestParam String month) {
        return ApiResponse.ok(monthly.monthly(user, month(month)));
    }

    @GetMapping("/monthly/patterns")
    ApiResponse<Map<String, Object>> patterns(@AuthenticationPrincipal User user, @RequestParam String month) {
        return ApiResponse.ok(monthly.patterns(user, month(month)));
    }

    @GetMapping("/monthly/recurring")
    ApiResponse<Map<String, Object>> recurring(@AuthenticationPrincipal User user, @RequestParam String month) {
        return ApiResponse.ok(monthly.recurring(user, month(month)));
    }

    @GetMapping("/monthly/comparison")
    ApiResponse<Map<String, Object>> comparison(@AuthenticationPrincipal User user, @RequestParam String month) {
        return ApiResponse.ok(monthly.comparison(user, month(month)));
    }

    @GetMapping("/monthly/insights")
    ApiResponse<Map<String, Object>> monthlyInsights(@AuthenticationPrincipal User user, @RequestParam String month) {
        return ApiResponse.ok(insights.monthly(user, month(month)));
    }

    @GetMapping("/challenges/monthly")
    ApiResponse<Map<String, Object>> challengeMonthly(@AuthenticationPrincipal User user, @RequestParam String month) {
        return ApiResponse.ok(aggregate.monthly(user, month(month)));
    }

    @GetMapping("/challenges/overall")
    ApiResponse<Map<String, Object>> overall(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(aggregate.overall(user));
    }

    @GetMapping("/challenges/{id}")
    ApiResponse<Map<String, Object>> challenge(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ApiResponse.ok(challenges.overview(user, id));
    }

    @GetMapping("/challenges/{id}/patterns")
    ApiResponse<Map<String, Object>> challengePatterns(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ApiResponse.ok(challenges.patterns(user, id));
    }

    @GetMapping("/challenges/{id}/insights")
    ApiResponse<Map<String, Object>> challengeInsights(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ApiResponse.ok(insights.challenge(user, id));
    }

    @GetMapping("/challenges/{id}/comparison")
    ApiResponse<Map<String, Object>> challengeComparison(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ApiResponse.ok(aggregate.comparison(user, id));
    }
}
