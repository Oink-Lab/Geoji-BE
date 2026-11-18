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
    "incomeCount": 1,
    "averageExpenseTransaction": 800.0,
    "dailyAverageExpense": 123.08,
    "categories": [
      {"code": "FOOD", "name": "식비", "amount": 1000, "count": 1, "averageTransaction": 1000.0, "sharePercent": 62.5, "rank": 1, "dailyAverage": 76.92},
      {"code": "HOUSING", "name": "주거·공과금", "amount": 600, "count": 1, "averageTransaction": 600.0, "sharePercent": 37.5, "rank": 2, "dailyAverage": 46.15}
    ]
  },
  "error": null
}
```

요약 응답의 각 `insights[]` 항목은 `code`, `message`, `evidence`를 포함합니다. 근거가 충분하지 않으면 `analysis.state=INSUFFICIENT_DATA`와 빈 목록을 반환합니다. 별도의 LLM 호출이나 분석 캐시는 사용하지 않습니다.

## 프론트엔드 연동 기준

- `month`는 `YYYY-MM`, `date`·`from`·`to`·`through`·`analysisThrough`는 `YYYY-MM-DD` 문자열입니다. `finishedAt`·`failureAt`·`confirmedAt`처럼 시각을 담은 필드는 ISO 8601 UTC 문자열(`...Z`) 또는 `null`입니다. ID는 UUID 문자열입니다.
- 미래 월 분석은 기존 명세와 API 계약대로 HTTP 400, `error.code=FUTURE_MONTH`를 반환합니다. 월 선택 UI에서 분석 요청을 막아 주세요. 거래가 없는 과거·현재 월은 정상 응답과 0/빈 목록으로 표시합니다.
- 실제 서버의 소비 카테고리 코드는 `FOOD`(식비), `TRANSPORT`(교통), `SHOPPING`(쇼핑), `HOUSING`(주거·공과금), `SUBSCRIPTION`(통신·구독), `LEISURE`(문화·여가), `HEALTH`(건강·운동), `EDUCATION`(교육), `GIFT`(경조사·선물), `ETC_EXPENSE`(기타)입니다. 입금은 `SALARY`(급여), `ALLOWANCE`(용돈), `SIDE_INCOME`(부수입), `REFUND`(환급·환불), `FINANCIAL_INCOME`(금융수익), `ETC_INCOME`(기타)입니다. 두 종류의 기타는 코드로 구분합니다. 화면의 기존 카테고리 목록과 다를 수 있으므로 서버의 `/api/v1/transactions/categories?type=EXPENSE` 또는 `type=INCOME`을 기준으로 연동해 주세요.
- 시작일은 DAY 1입니다. 일일 권장액은 **남은 예산 ÷ 오늘 포함 남은 일수의 올림값**입니다. 마지막 날의 남은 일수는 1이므로 0으로 나누지 않습니다. `difference=allowance-spent`가 0이면 권장액을 지킨 날로 집계합니다. 프론트의 임시 내림 계산과 다르므로 화면 계산을 맞춰야 합니다.
- DB의 `category_code`, `source`는 NOT NULL이며 애플리케이션에서도 필수값입니다. 분석의 거래·카테고리 응답은 이 스키마를 전제로 합니다.

### 필드와 타입

공통으로 `long` 금액·건수는 JSON 정수, 비율·평균·표준편차는 JSON 숫자(소수 둘째 자리 반올림)입니다. `null`은 계산 불가·기록 없음이고 0은 계산된 0입니다. 아래 중첩 구조는 여러 경로에서 재사용합니다.

| 구조 | 필드와 타입 |
| --- | --- |
| `analysis` | `state: string`, `sufficientData: boolean` |
| `category` | `code: string`, `name: string`, `amount: integer`, `count: integer`, `averageTransaction: number`, `sharePercent: number`, `rank: integer`, `dailyAverage: number` |
| `change` | `previous: integer\|number`, `current: integer\|number`, `difference: integer\|number`, `changePercent: number\|null`, `comparable: boolean` |
| `spendingDay` | `date: YYYY-MM-DD`, `amount: integer` |
| `largestTransaction` | `id: UUID`, `date: YYYY-MM-DD`, `amount: integer`, `category: string`, `source: string` |
| `insight` | `code: string`, `message: string`, `evidence: object` (근거 지표별 키는 `code`에 따름) |

| 경로의 `data` | 최상위 필드와 타입 |
| --- | --- |
| `/monthly` | `month: YYYY-MM`, `through: date`, `userId: UUID`, `analysis: object`, `totalExpense/totalIncome: integer`, `expenseCount/incomeCount/expenseDays: integer`, `averageExpenseTransaction/dailyAverageExpense/topCategorySharePercent: number`, `categories/topThreeCategories: category[]`, `mostSpentCategory/leastSpentCategory: category\|null` |
| `/monthly/patterns` | `month`, `through`, `userId`, `days: {date,expense,income,expenseCount}[]`, `mostSpentDay/leastSpentDayWithExpense: spendingDay\|null`, `daysWithoutRecordedExpense: integer`, `largestTransaction: object\|null`, `topThreeDays: spendingDay[]`, `concentration: {topOnePercent,topThreePercent,topFivePercent:number,topFiveDays:spendingDay[]}`, `weeks: {week:integer,from:date,to:date,expense:integer,dailyAverage:number,count:integer,topCategory:category\|null}[]` |
| `/monthly/recurring` | `month`, `through`, `userId`, `recurringExpense/ordinaryExpense/recurringCount: integer`, `recurringSharePercent: number`, `largestRecurringTransaction: object\|null`, `recurringCategories: category[]` |
| `/monthly/comparison` | `month`, `userId`, `currentMonthPartial: boolean`, `fullPreviousMonthComparison/sameElapsedDaysComparison: comparison\|null`. `comparison`에는 `previousFrom/previousThrough/currentFrom/currentThrough: date`, `totalExpense/dailyAverage/expenseCount/averageTransaction/recurringSharePercent/daysWithoutRecordedExpense: change`, `categories: {코드:change}`가 있습니다. 같은 경과일 비교는 완료된 월에 `null`입니다. |
| `/monthly/insights` | `month`, `userId`, `analysis`, `insights: insight[]` |
| `/challenges/monthly` | `month`, `through`, `userId`, `participationDays/daysWithinAllowance/daysOverAllowance/challengeExpense/recurringExpenseWithinChallenges/successConfirmed/failureConfirmed/abandoned: integer` |
| `/challenges/overall` | `userId`, `totalCreated/scheduled/inProgress/completed/success/failed/abandoned: integer`, `successRatePercent: number\|null`, `successfulTotalBudget/successfulTotalSpent/cumulativeUnusedBudget: integer`, `averageBudgetUsePercent/averageDailySpent/averageParticipationDays/averageUnusedBudget/averageAllowanceCompliancePercent/averageRecurringSharePercent: number\|null`, `currentSuccessStreak/longestSuccessStreak: integer`, `recentResults: {id,status,finishedAt}[]`, `mostFrequentlyTopCategory/mostOftenConcentratedSegment: string\|null`, `overAllowanceDayCategories: {category,amount}[]` |
| `/challenges/{id}` | `challengeId/userId: UUID`, `status: string`, `analysisThrough: date`, `provisional: boolean`, `totalBudget/cumulativeSpent/remainingBudget/unusedBudget/budgetDifference: integer`, `budgetUsePercent/averageDailySpent/averageDailyRecommended/periodProgressPercent/budgetUseMinusPeriodProgressPoints/allowanceCompliancePercent/averageDailyDifference: number`, `initialDailyAllowance/maxDailySaving/maxDailyOverageAmount: integer`, `elapsedDays/durationDays/daysWithinAllowance/daysOverAllowance: integer`, `dailyTrend: day[]`, `analysis` |
| `/challenges/{id}/patterns` | `challengeId`, `analysisThrough`, `segments: segment[]`, `volatility: {dailyMean:number,maximum/minimum:integer,standardDeviation:number,coefficientOfVariation:number\|null,comparable:boolean}`, `streaks: {longestWithinAllowance,longestOverAllowance,currentWithinAllowance,currentOverAllowance,daysWithoutRecordedExpense:integer}`, `categories/topThreeCategories/recurringCategories: category[]`, `recurringSharePercent: number`, `concentration: {mostSpentDay,leastSpentDayWithExpense:spendingDay\|null,largestTransaction:object\|null,topThreeDays:spendingDay[],topThreeDaysSharePercent:number}` |
| `/challenges/{id}/insights` | `challengeId/userId`, `status`, `analysisThrough`, `analysis`, `outcome: object\|null`, `insights: insight[]`. 실패 시 `outcome.atConfirmation`은 저장된 `categories`, `topTransactions`, `maxSpendingDay`, `topThreeDays`, 집중도·고정지출 비율을 포함하며 과거 기록이 없으면 해당 필드는 `null`입니다. `outcome.currentRecalculation`은 현재 거래 기준입니다. |
| `/challenges/{id}/comparison` | 직전 도전이 없으면 `challengeId`, `comparable:false`, `reason:NO_PREVIOUS_CHALLENGE`. 있으면 `previousChallengeId`, `currentProvisional`, `currentThrough/previousThrough: date`, `totalBudget/durationDays/totalExpense/dailyAverage/budgetUsePercent/unusedBudget/allowanceCompliancePercent/daysWithoutRecordedExpense/daysWithoutRecordedExpensePercent/longestWithinAllowance/longestOverAllowance/recurringSharePercent/dailyStandardDeviation: change`, `categories: {코드:{amount:change,dailyAverage:change}}`, `previousStatus/currentStatus`, `comparisonBasis`가 추가됩니다. |

`day`는 `date: date`, `day: integer`, `allowance/spent/difference/cumulativeSpent/remainingBudget: integer`, `budgetUsePercent/periodProgressPercent/depletionGapPoints: number`입니다. `segment`는 `segment: EARLY\|MIDDLE\|LATE`, `from/to: date`, `expense: integer`, `dailyAverage/sharePercent/budgetUsePoints/periodSharePercent/depletionGapPoints: number`, `topCategory: category\|null`, `daysOverAllowance: integer`입니다. 1일은 `EARLY`만, 2일은 `EARLY`·`LATE`만 반환합니다.

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
