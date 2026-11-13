# Phase 2 거래 및 고정지출 API

모든 경로는 `Authorization: Bearer <accessToken>`을 요구하고 기존 `ApiResponse` 형식으로 응답합니다. 금액은 양수 원화 정수이며 소비와 입금은 `type`으로 구분합니다. `FOOD`에는 식사, 카페, 간식이 모두 포함됩니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/transactions/categories?type=EXPENSE` | 유형별 카테고리 코드와 이름 |
| GET | `/api/v1/transactions/methods` | 거래수단 코드와 이름 |
| POST | `/api/v1/transactions` | 거래 생성 |
| GET | `/api/v1/transactions/{id}` | 거래 상세 |
| PATCH | `/api/v1/transactions/{id}` | 거래 수정 |
| DELETE | `/api/v1/transactions/{id}` | 거래 삭제 |
| GET | `/api/v1/transactions?date=YYYY-MM-DD` | 날짜별 거래, 페이징 |
| GET | `/api/v1/transactions?month=YYYY-MM` | 월별 거래, 페이징 |
| GET | `/api/v1/transactions/summary?month=YYYY-MM` | 소비·입금 합계 |
| GET | `/api/v1/transactions/calendar?month=YYYY-MM` | 날짜별 소비·입금 합계 |
| POST | `/api/v1/recurring-expenses` | 고정지출 등록 |
| GET | `/api/v1/recurring-expenses` | 고정지출 목록 |
| GET | `/api/v1/recurring-expenses/{id}` | 고정지출 상세 |
| PATCH | `/api/v1/recurring-expenses/{id}` | 향후 결제 설정 수정 |
| PATCH | `/api/v1/recurring-expenses/{id}/pause` | 일시정지 |
| PATCH | `/api/v1/recurring-expenses/{id}/resume` | 재개 |
| DELETE | `/api/v1/recurring-expenses/{id}` | 해지 |
| GET | `/api/v1/recurring-expenses/schedule?month=YYYY-MM` | 예정 결제 조회 |

거래 목록의 `page`는 0부터, `size`는 1~100까지 지정할 수 있습니다. 거래 수정 시 생략한 필드는 유지됩니다. 자동 거래를 삭제해도 월별 생성 이력은 남아 재생성을 막습니다. 스케줄러는 서울 시간 매일 00:05에 실행되며 서버 시작 시 누락된 활성 기간의 기록을 복구합니다. 일시정지·재개·해지 전에는 해당 시점까지 활성 기간의 누락분을 기록합니다. 예정 금액은 거래 합계에 포함되지 않습니다.

일반 가계부는 소비·입금을 각각 양수로 반환하며, 색상은 프론트엔드에서 적용합니다. 향후 챌린지의 `+`·`-` 표시는 일일 권장금액 대비 절약·초과 차이를 뜻하며 거래 유형과 별개입니다.
