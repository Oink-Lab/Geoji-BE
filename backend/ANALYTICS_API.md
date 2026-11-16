# 소비 및 챌린지 분석 API

모든 경로는 `Authorization: Bearer <accessToken>`을 요구하고 기존 `ApiResponse` 형식으로 응답합니다. 월 파라미터는 `YYYY-MM`입니다. 날짜와 진행 중 월의 오늘 기준은 `Asia/Seoul`입니다. 미래 월은 `FUTURE_MONTH` 오류로 거절합니다.

| 메서드 | 경로 | 주요 데이터 |
| --- | --- | --- |
| GET | `/api/v1/analytics/monthly?month=2026-11` | 월간 소비·입금, 거래 건수, 평균, 카테고리 순위 |
| GET | `/api/v1/analytics/monthly/patterns?month=2026-11` | 일별 지표, 무기록 일수, 소비 집중도, 1~7일 단위 주간 흐름 |
| GET | `/api/v1/analytics/monthly/recurring?month=2026-11` | 실제 자동 기록 거래와 일반 거래 비교 |
| GET | `/api/v1/analytics/monthly/comparison?month=2026-11` | 전월 전체 및 현재 월 동일 경과일 비교 |
| GET | `/api/v1/analytics/monthly/insights?month=2026-11` | 근거를 포함한 규칙 기반 월간 요약 |
| GET | `/api/v1/analytics/challenges/monthly?month=2026-11` | 월에 속하는 참여일과 확정 결과 횟수 |
| GET | `/api/v1/analytics/challenges/overall` | 전체 이력, 성공률, 연속 성공, 반복 패턴 |
| GET | `/api/v1/analytics/challenges/{id}` | 예산·권장액 준수·일별 잔액 추이 |
| GET | `/api/v1/analytics/challenges/{id}/patterns` | 구간·변동성·연속 기록·카테고리·집중도 |
| GET | `/api/v1/analytics/challenges/{id}/insights` | 성공·실패·포기 분석과 근거 기반 요약 |
| GET | `/api/v1/analytics/challenges/{id}/comparison` | 직전 챌린지와 정규화 지표 비교 |

## 계산 기준

- 금액은 기존 `transactions`의 원화 정수입니다. `EXPENSE`와 `INCOME`은 별도로 집계하며 입금을 소비에서 차감하지 않습니다. 삭제된 거래와 고정지출 예정액은 포함하지 않습니다.
- 현재 월은 1일부터 오늘까지, 종료된 월은 말일까지 분석합니다. `dailyAverageExpense = totalExpense / 분석 대상 날짜 수`입니다. `daysWithoutRecordedExpense`는 해당 기간에 **소비가 기록되지 않은 날짜 수**이며 실제 무소비를 증명하지 않습니다.
- 카테고리 비율과 고정지출 비율은 각 금액을 전체 소비로 나눕니다. 동액 카테고리는 코드 순서, 동액 날짜는 날짜 순서로 정렬합니다. 소비가 0원이면 비율은 0, 최대·최소 항목은 `null`입니다.
- 주간 구간은 1~7일, 8~14일, 15~21일, 22~28일, 29일~말일입니다. 진행 중인 월의 미래 날짜만 포함하는 구간은 반환하지 않습니다.
- 전월 비교의 `changePercent`는 이전 값이 0이면 `null`, `comparable`은 `false`입니다. 현재 월은 `sameElapsedDaysComparison`으로 전월 같은 경과일도 별도로 제공합니다. 전월 일수가 부족하면 전월 말일까지만 비교합니다.
- 고정지출은 `source=RECURRING`인 실제 거래만 사용합니다. 사용자가 금액을 바꾸면 변경된 금액을, 거래를 삭제하면 제외한 결과를 다음 조회에서 반환합니다.
- 챌린지 분석 기간은 시작일부터 오늘·종료일·실패/포기일 중 가장 이른 날까지입니다. 예약 상태와 이후 날짜는 분석하지 않습니다. 권장금액은 기존 `ChallengeCalculator`의 동적 예산 공식을 사용합니다. `dailyDifference = allowance - spent`이며 음수는 일일 권장액 초과입니다. 실패는 총예산 초과로만 판정합니다.
- `budgetUsePercent = cumulativeSpent / totalBudget × 100`이며 100%를 초과할 수 있습니다. `unusedBudget`은 실제 저축액이 아닌 미사용 예산입니다. 변동성의 표준편차는 분석 대상 전체 날짜의 일별 소비로 계산합니다. 데이터가 2일 미만이거나 평균 0이면 변동계수는 `null`입니다.
- 새로 실패가 확정되는 챌린지는 같은 트랜잭션에서 `failure_analysis_snapshot`에 카테고리별 금액·비중, 상위 거래 3건, 최대 소비일과 상위 1·3일 집중도, 실제 고정지출 금액·비중을 기록합니다. `atConfirmation`은 이 불변 기록을, `currentRecalculation`은 현재 거래 상태를 사용합니다. 이후 거래 변경은 실패 상태나 당시 기록을 수정하지 않습니다. 기존 실패 건에 기록되지 않은 상세값은 `null`이며 `categoryBreakdownAvailable=false`입니다.
- 성공률의 분모는 성공+실패입니다. 포기는 별도 집계합니다. 월간 챌린지 참여일은 각 월에 속한 날짜만 세고 성공·실패·포기 횟수는 `finishedAt`이 속한 월에 한 번만 집계합니다. 예약 취소는 기존 정책대로 삭제되므로 생성 횟수에 포함되지 않습니다.
- 모든 조회는 인증된 사용자 ID로 거래와 챌린지를 제한합니다. 챌린지 ID가 다른 사용자에게 속하면 `CHALLENGE_NOT_FOUND`를 반환합니다.

## 응답 예시

```json
{
  "success": true,
  "data": {
    "month": "2026-11",
    "through": "2026-11-13",
    "totalExpense": 1600,
    "totalIncome": 300,
    "expenseCount": 2,
    "dailyAverageExpense": 123.08,
    "categories": [
      {"code": "FOOD", "name": "식비", "amount": 1000, "count": 1, "sharePercent": 62.5, "rank": 1}
    ]
  },
  "error": null
}
```

요약 응답의 각 `insights[]` 항목은 `code`, `message`, `evidence`를 포함합니다. 근거가 충분하지 않으면 `analysis.state=INSUFFICIENT_DATA`와 빈 목록을 반환합니다. 별도의 LLM 호출이나 분석 캐시는 사용하지 않습니다.

## 검증

`./gradlew :backend:test :backend:bootJar` 또는 Windows에서 `.\gradlew.bat :backend:test :backend:bootJar`를 실행합니다. 기본 통합 테스트는 H2 테스트 프로필과 고정된 서울 시간대를 사용합니다.

전용 PostgreSQL 데이터베이스로 집계·날짜 연산·마이그레이션을 확인하려면 `POSTGRES_ANALYTICS_TEST_URL`, `POSTGRES_ANALYTICS_TEST_USER`, `POSTGRES_ANALYTICS_TEST_PASSWORD`를 설정한 뒤 `:backend:test --tests com.geoji.app.analytics.PostgresAnalyticsIntegrationTest`를 실행합니다. URL은 `jdbc:postgresql://호스트:포트/전용테스트DB` 형식입니다. URL이 없으면 해당 테스트는 건너뜁니다. 테스트 데이터는 롤백되지만 Flyway 스키마는 전용 DB에 남으므로 운영 DB를 사용하지 마세요.

## 기능과 검증 코드

| 기능 | Service 메서드 | 주요 응답 필드 | 테스트 |
| --- | --- | --- | --- |
| 월간 소비·카테고리 | `MonthlyAnalyticsService.monthly` | `totalExpense`, `totalIncome`, `categories` | `AnalyticsIntegrationTest.monthlySeparatesIncomeRecurringFutureAndOtherUsersAndRecalculates` |
| 날짜·주간·집중도 | `MonthlyAnalyticsService.patterns` | `days`, `weeks`, `concentration` | 위 테스트, `previousMonthZeroAndLeapMonthBoundariesAreExplicit` |
| 고정지출 | `MonthlyAnalyticsService.recurring` | `recurringExpense`, `recurringSharePercent` | `monthlySeparatesIncomeRecurringFutureAndOtherUsersAndRecalculates` |
| 전월·동일 경과일 비교 | `MonthlyAnalyticsService.comparison` | `fullPreviousMonthComparison`, `sameElapsedDaysComparison` | `previousMonthZeroAndLeapMonthBoundariesAreExplicit`, `MonthlyAnalyticsServiceTest.alignedComparisonCapsBothMonthsWhenPreviousMonthIsShorter` |
| 챌린지 예산·일별 추이 | `ChallengeAnalyticsService.overview` | `dailyTrend`, `budgetUsePercent`, `allowanceCompliancePercent` | `challengeBudgetPatternsOwnershipAndSnapshotStaySeparate` |
| 구간·변동성·연속 기록 | `ChallengeAnalyticsService.patterns` | `segments`, `volatility`, `streaks` | `challengeBudgetPatternsOwnershipAndSnapshotStaySeparate`, `shortAbandonedChallengeExcludesLaterDays` |
| 성공·실패·포기 분석 | `InsightService.challenge` | `outcome.atConfirmation`, `outcome.currentRecalculation`, `insights[].evidence` | `newFailureCapturesDetailedFactsAndKeepsThemAfterTransactionChange`, `shortAbandonedChallengeExcludesLaterDays` |
| 직전 도전 비교 | `ChallengeAggregateService.comparison` | `dailyAverage`, `budgetUsePercent`, `categories` | `challengeComparisonAndOverallUseNormalizedMetrics` |
| 누적·월간 통계 | `ChallengeAggregateService.overall`, `monthly` | `successRatePercent`, `recentResults`, `participationDays` | `challengeComparisonAndOverallUseNormalizedMetrics`, `crossMonthChallengeCountsOnlyRelevantDaysAndFinalizationMonth` |
| 규칙 기반 요약 | `InsightService.monthly`, `challenge` | `insights[].message`, `insights[].evidence` | `emptyMonthHasNoInventedRankingOrInsight`, `newFailureCapturesDetailedFactsAndKeepsThemAfterTransactionChange` |
