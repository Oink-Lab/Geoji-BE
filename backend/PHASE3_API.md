# Phase 3 챌린지 API

모든 API는 기존 JWT 인증과 `{ "success": true, "data": ..., "error": null }` 응답 형식을 사용합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/v1/challenges` | 챌린지 생성 |
| GET | `/api/v1/challenges/current` | 예약 또는 진행 중인 챌린지 |
| GET | `/api/v1/challenges/history` | 종료된 챌린지 목록 |
| GET | `/api/v1/challenges/{id}` | 상세 |
| GET | `/api/v1/challenges/{id}/progress` | 오늘의 진행 현황 |
| GET | `/api/v1/challenges/{id}/calendar` | 기간별 일일 계산 |
| GET | `/api/v1/challenges/{id}/result` | 종료 결과 |
| GET | `/api/v1/challenges/{id}/retry` | 재도전 입력 기본값 |
| POST | `/api/v1/challenges/{id}/abandon` | 진행 중 포기 |
| POST | `/api/v1/challenges/{id}/cancel` | 시작 전 예약 취소 |

생성 예시:

```json
{
  "name": "커피값 절약",
  "totalBudget": 70000,
  "durationDays": 7,
  "startDate": "2026-11-12"
}
```

`name`과 `startDate`는 생략할 수 있습니다. `startDate`를 생략하면 한국 날짜 기준 오늘 시작합니다. `durationDays`는 1~30일이며 시작일을 포함합니다. 생성 요청과 재도전 기본값에 챌린지 전용 알림 시간은 없습니다. 챌린지는 공통 사용자 알림 설정을 공유하며, 실제 푸시는 Phase 7에서 처리합니다.

일일 권장 금액은 해당 날짜 이전까지의 지출을 뺀 남은 예산을 남은 일수로 나눈 값을 원 단위로 올림합니다. EXPENSE 거래만 집계하고 INCOME은 제외합니다. 거래 날짜를 기준으로 기존 `transactions` 테이블을 조회하므로 거래를 생성·수정·삭제하면 계산값이 바뀝니다. 총예산을 초과한 순간 FAILED로 확정하고 실패 당시 지출과 시각을 저장합니다. 실패 이후 거래가 바뀌어도 FAILED 상태와 최초 실패 기록은 유지합니다. SUCCESS는 마지막 날 다음 날 00:00 이후 확정합니다.

서버 시작 및 한국 시간 00:00에 예약 시작과 종료 판정을 복구합니다. 예약 취소는 행을 삭제합니다. 종료된 챌린지는 그 종료일의 다음 날짜부터 다시 시작할 수 있습니다.

## 홈 화면 조회

인증된 사용자의 홈 화면에서는 `GET /api/v1/challenges/current`와
`GET /api/v1/challenges/history`를 호출합니다. `/current`의 `data`가 `null`이면
예약·진행 중인 챌린지가 없습니다. 이때 `/history`의 `data`가 `[]`이면 종료된
챌린지도 없고, 목록이 있으면 과거 챌린지가 있습니다. 예약 취소는 행을 삭제하므로
취소한 예약은 이력에 남지 않습니다.

`/history`는 종료 시각(`finishedAt`) 내림차순이며, 시각이 같으면 시작일
내림차순입니다. 첫 항목의 `id`, `status`, `name`으로 가장 최근 종료된
챌린지의 홈 카드와 이동 버튼을 구성할 수 있습니다. `SUCCESS` 또는 `FAILED`면
`GET /api/v1/challenges/{id}/result`로 결과를 조회하고,
`GET /api/v1/challenges/{id}/retry`로 재도전 입력 기본값을 조회할 수 있습니다.
