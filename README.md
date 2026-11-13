# 거지앱 백엔드

Java 17, Spring Boot 3.5.10, Gradle 8.14.5를 사용합니다. 로컬에 있던 버전에 맞춰 구성했습니다. PostgreSQL 17과 Redis는 `compose.yml`로 실행하며 AI 서비스는 별도 FastAPI 애플리케이션입니다.

## 실행

1. 루트의 `.env.example`을 `.env`로 복사합니다. 로컬 `.env`에는 전달받은 Google·Kakao 설정과 무작위 JWT 키가 이미 들어 있습니다. `.env`와 개인키 파일은 Git에서 제외됩니다.
2. `docker compose up -d`로 PostgreSQL과 Redis를 시작합니다. Docker Desktop을 먼저 켜야 합니다.
3. Windows: `.\gradlew.bat :backend:bootRun` / macOS·Linux: `./gradlew :backend:bootRun`.
4. 테스트: `.\gradlew.bat :backend:test` 또는 `./gradlew :backend:test`.
5. JAR: `.\gradlew.bat :backend:bootJar` 또는 `./gradlew :backend:bootJar`.

처음 빌드할 때 Gradle 플러그인과 의존성을 Maven Central에서 내려받아야 할 수 있습니다.

## 환경 변수

`.env.example`에 모든 이름이 있습니다. `JWT_SECRET_BASE64`는 최소 32바이트의 암호학적 난수를 Base64로 인코딩한 값이어야 합니다. `POSTGRES_PASSWORD`도 별도 강한 값으로 설정합니다. `OAUTH_CALLBACK_BASE_URL`은 외부에서 접근 가능한 백엔드 주소이고, `OAUTH_SUCCESS_REDIRECT_URL`은 인증 완료 후 **허용된 고정 앱 주소**입니다. `CORS_ORIGINS`로 브라우저 출처를 지정할 수 있습니다.

Google 콘솔의 승인된 리디렉션 URI: `{OAUTH_CALLBACK_BASE_URL}/api/v1/auth/oauth/google/callback`. Kakao 개발자 콘솔: `{OAUTH_CALLBACK_BASE_URL}/api/v1/auth/oauth/kakao/callback`. Apple Developer 설정: `{OAUTH_CALLBACK_BASE_URL}/api/v1/auth/oauth/apple/callback`과 해당 서비스 ID, Team ID, Key ID, `.p8` 개인키 경로가 필요합니다. Apple 환경 값이 비어 있으면 시작 API가 `OAUTH_NOT_CONFIGURED`를 반환합니다. 실제 공급자 콘솔 등록과 실계정 로그인은 별도 확인이 필요합니다.

전달받은 Google Client ID는 동일한 문자열이 두 번 이어져 있었으므로 `.env`에는 한 번만 넣었습니다. Google·Kakao 자격 증명은 코드와 `.env.example`에 넣지 않았습니다. 채팅으로 공유된 비밀값이므로 실제 배포 전 공급자 콘솔에서 재발급하는 편이 안전합니다.

## API

응답은 `{ "success": true, "data": ..., "error": null }` 형식입니다. 보호 API는 `Authorization: Bearer <accessToken>`을 요구합니다.

서버 실행 후 Swagger UI는 `http://localhost:8080/swagger-ui.html`, OpenAPI JSON은
`http://localhost:8080/v3/api-docs`에서 확인할 수 있습니다. 두 문서 경로는 로그인 없이
열립니다. Swagger UI의 **Authorize**에 로그인 응답의 accessToken을 입력하면 보호
API를 시험할 수 있습니다. 실제 API의 JWT 인증 요구사항은 그대로 적용됩니다.

| 메서드 | 경로 | 내용 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 이메일·비밀번호·닉네임 가입 |
| POST | `/api/v1/auth/login` | Access/Refresh JWT 발급 |
| POST | `/api/v1/auth/refresh` | Refresh Token 1회용 교체 |
| POST | `/api/v1/auth/logout` | Access 블랙리스트·Refresh 무효화 |
| GET | `/api/v1/users/me` | 내 정보 |
| PATCH | `/api/v1/users/me` | 닉네임 수정 |
| PATCH | `/api/v1/users/me/password` | 비밀번호 변경·기존 토큰 폐기 |
| DELETE | `/api/v1/users/me` | 탈퇴·기존 토큰 폐기 |
| GET | `/api/v1/auth/oauth/{google,kakao,apple}/start` | 공급자 인증 시작 |
| GET/POST | `/api/v1/auth/oauth/{provider}/callback` | 공급자 콜백, Apple은 POST |
| POST | `/api/v1/auth/oauth/exchange` | 1분 유효 1회용 코드를 자체 JWT로 교환 |

기존 계정에 소셜 계정을 연결할 때는 로그인한 상태에서 `GET /api/v1/auth/oauth/{provider}/start?link=true`를 호출합니다. 로그인 시 이메일만 같아도 자동 연결하지 않습니다.

```powershell
curl.exe -X POST http://localhost:8080/api/v1/auth/signup -H "Content-Type: application/json" -d '{"email":"me@example.com","password":"example-password","nickname":"거지"}'
curl.exe -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"me@example.com","password":"example-password"}'
curl.exe http://localhost:8080/api/v1/users/me -H "Authorization: Bearer ACCESS_TOKEN"
curl.exe -X POST http://localhost:8080/api/v1/auth/refresh -H "Content-Type: application/json" -d '{"refreshToken":"REFRESH_TOKEN"}'
```

Access Token은 30분, Refresh Token은 14일입니다. Refresh 세션과 Access 블랙리스트는 Redis에 저장합니다. Redis를 사용할 수 없으면 인증 요청은 실패합니다. 비밀번호 변경·탈퇴 시 `authVersion`을 증가시켜 모든 기존 토큰을 거절합니다. 탈퇴한 회원의 개인정보 삭제·보존 세부 정책은 추가 결정이 필요합니다.

## AI 서비스

Python 3.12 환경에서 `cd ai-service`, `pip install -r requirements.txt`, `uvicorn app.main:app --reload`를 실행하면 `/health`를 확인할 수 있습니다. 이 컴퓨터에는 Python 실행 파일이 없어 로컬 실행은 검증하지 못했습니다.
