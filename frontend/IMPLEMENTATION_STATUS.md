# 메디바이스 구현 현황

- 최종 갱신일: 2026-09-03
- 프로젝트: `medivice-vue` (front) + `medivice`(Spring Boot, ../src)
- 기준 문서: `medivice-frontend-handoff.html`, `medivice-sprint-plan.html`, DA팀 DB 스키마(`01_schema_ddl.sql` 등)

## 1. 현재 완료 수준

프론트엔드·백엔드·DB·AI(비전 OCR)가 전부 실제로 연결되어 동작합니다. Sprint 1(성분 코어)의 핵심 흐름은 물론, Sprint 2로 분류됐던 사진 등록(OCR)까지 실제 비전 AI로 구현되어 있습니다. 남은 gap은 "5. 미구현·gap" 절 참고.

## 2. 화면 및 라우트

| 화면 ID | 화면 | Route | 구현 상태 |
| --- | --- | --- | --- |
| SCR-AUTH-001 | 로그인 | `/login` | **실제 API 연결** — `POST /api/auth/login` |
| SCR-AUTH-002 | 회원가입 | `/signup` | **실제 API 연결** — `POST /api/auth/signup` |
| SCR-ONB-001 | 문진 진행 여부 | `/onboarding` | 화면 흐름만(로컬) |
| SCR-ONB-002 | 특이사항 등록 | `/onboarding/profile` | 화면 흐름만(로컬, 저장 API 없음) |
| SCR-ONB-003 | 초기 복용 항목 등록 | `/onboarding/medications` | 실제 등록 화면으로 연결(직접 입력) |
| SCR-MAIN-001 | 메인 및 빈 상태 | `/main` | **실제 API 연결** — `GET /api/dashboard` |
| SCR-MAIN-002 | 메디라이트 상세 | `/medilight` | **실제 API 연결** |
| SCR-MAIN-003 | 복용 중인 약 목록 | `/medications` | **실제 API 연결** |
| SCR-REG-001 | 사진 기반 약 등록 | `/main/register` | **실제 API 연결** — `POST /api/medications/ocr` |
| SCR-REG-002 | OCR 인식 결과 확인 | `/main/register-confirm` | **실제 API 연결** — 다중 약 지원 |
| SCR-REG-003 | 수기 등록 | `/main/register-manual` | **실제 API 연결** — `POST /api/medications` |
| SCR-SE-001 | 증상 기록 | `/main/symptom` | **실제 API 연결** — `POST /api/symptoms` |
| SCR-MY-001 | 마이페이지 | `/my` | 조회만(로컬 상태) |
| SCR-MY-002 | 특이사항 관리 | `/my/profile` | 조회 화면만, 저장 API 없음 |
| SCR-MY-003 | 계정 관리 | `/my/account` | 조회 화면만, 가족 연동 비활성 |
| SCR-MY-004 | 증상 기록 모음 | `/my/symptoms` | 대시보드에서 받은 실제 데이터 표시 |
| SCR-RPT-001 | 보고서 생성 · 결과 | `/report/*` | **실제 API 연결** — `POST /api/reports` |
| 공통 | 찾을 수 없는 주소 | `/:pathMatch(.*)*` | 완료 |

## 3. 핵심 사용자 흐름

### 3.1 인증

1. 회원가입: 아이디·비밀번호·성별·생년월일 입력 → `POST /api/auth/signup` → 실제 `users` 테이블에 생성(이미 있는 아이디면 그대로 재사용, 비밀번호는 검증하지 않음 — Sprint 1 축소 범위) → 응답으로 받은 `username`을 `localStorage`(`medivice_login_id`)에 저장.
2. 이후 모든 API 요청에 `X-Medivice-User: <encodeURIComponent(아이디)>` 헤더를 실어 보내고, 백엔드는 이 헤더로 사용자를 식별합니다(세션·토큰 없음).
3. 로그인: `POST /api/auth/login` — 존재하지 않는 아이디면 404, 화면에 에러 표시.
4. 새로고침해도 `localStorage`의 아이디로 동일 사용자로 계속 식별됩니다.

### 3.2 핵심 E2E 데모 (수기 등록)

1. `/main/register-manual`에서 제품명·성분·함량·용법·등록 사유 입력.
2. `POST /api/medications`(성분은 배열 — 복합제 지원) → DB에 실제 저장.
3. 응답으로 최신 `medilight`(성분별 합산·중복·중복 판정)를 즉시 반영.
4. 동일 성분을 다른 제품명으로 한 번 더 등록하면 실제로 초록→노랑 전환을 확인할 수 있습니다(DB 뷰 `v_overdose`가 판정).

### 3.3 사진 등록(OCR, 실제 비전 AI)

1. `/main/register`에서 사진 선택 → "인식 시작" → `POST /api/medications/ocr`(multipart).
2. 백엔드가 OpenAI(`GPT-5.2`, 구조화 출력)로 사진을 분석. **약봉투처럼 사진 한 장에 서로 다른 약이 여러 개 있으면 전부 각각 분리해서 배열로 반환**합니다(복합제=한 약에 성분 여러 개와는 다른 개념 — 프롬프트가 이 둘을 구분하도록 명시되어 있음).
3. `/main/register-confirm`에 약마다 카드로 표시. 각 카드에 인식 신뢰도(`confidence`, D-4 원칙)와 AI가 남긴 참고사항(`note`)이 함께 뜨고, 모든 필드는 직접 수정 가능.
4. 항목마다 체크박스로 등록 여부 선택 → "선택한 N건 등록" → 체크된 것만 순서대로 `POST /api/medications` 호출.
5. AI가 이름을 확신하지 못하면(글자가 흐림 등) 지어내지 않고 빈 값 또는 낮은 신뢰도로 남기도록 프롬프트에 명시(안전 원칙).

### 3.4 삭제

1. `DELETE /api/medications/:id` → 소프트 삭제(`ended_at`) → 응답 본문 없음(204)이라 별도로 `GET /api/medilight`를 호출해 최신 판정을 반영.

### 3.5 증상 기록

1. 날짜·작성 시각·증상 다중 선택·메모 입력 → `POST /api/symptoms`.
2. 저장 시점의 활성 복용 목록을 백엔드가 값 복사로 스냅샷(사용자가 직접 고르지 않음).

### 3.6 보고서

1. 기간·언어 선택 → `POST /api/reports`.
2. 백엔드가 그 기간의 복용 항목·주의/높은 주의 건수·증상 기록 건수를 집계하고, AI(요약 문장만)로 2~3문장 풀어씀 → 동기 처리로 즉시 `COMPLETED` 응답.

## 4. 공통 컴포넌트 / 상태 관리

`src/stores/medivice.js`가 사용자·복용 목록·증상·메디라이트·OCR 초안(`ocrDrafts`, 배열)·보고서 상태를 관리하며, 모든 쓰기 동작(`signup`/`login`/`createMedication`/`removeMedication`/`addSymptom`/`runOcr`/`createReport`)이 `src/api/client.js`를 통해 실제 백엔드를 호출합니다. Pinia HMR(`acceptHMRUpdate`)을 명시적으로 붙여, 개발 중 스토어 파일을 고쳐도 이미 열린 화면이 새 함수를 못 찾는 문제가 재발하지 않도록 해 두었습니다.

## 5. 미구현 · 알려진 gap

우선순위 순:

1. 로그인·회원가입의 실제 비밀번호 검증(현재는 아이디만 확인) — Sprint 3으로 문서화된 축소 범위.
2. 온보딩/마이페이지 특이사항(지병·알레르기·키·몸무게) 저장 API.
3. 세션 만료·전역 재시도 정책.
4. 실제 다국어 번역(현재 KO/EN 토글은 UI 상태만).
5. 보고서 PDF·QR 공유.
6. 가족 계정 연동(UC30).
7. 단위·컴포넌트·E2E 테스트.
8. 브라우저 시각 검토 전체 화면 순회(작업 환경에 연결 가능한 브라우저가 없어 curl 기반 API 검증으로 대체함 — 기능 로직은 실제 검증됨, 레이아웃/반응형은 미검증).

## 6. 검증 방법

브라우저 자동화 없이, 실제 curl로 매 기능을 엔드투엔드 검증했습니다(요청 → DB 반영 → 응답 확인). 예:

```sh
# 회원가입 → 대시보드 조회 (해당 사용자만 보임을 확인)
curl -s -X POST http://localhost:8080/api/auth/signup -H "Content-Type: application/json" \
  -d '{"loginId":"demo2","password":"x","sex":"여성","birthDate":"1990-01-01"}'
curl -s -H "X-Medivice-User: demo2" http://localhost:8080/api/dashboard

# 복합제 등록 (성분 2개)
curl -s -X POST http://localhost:8080/api/medications -H "Content-Type: application/json" \
  -d '{"type":"PRESCRIPTION","name":"아모잘탄정 5/50mg","ingredients":[{"name":"암로디핀","amount":5,"unit":"mg"},{"name":"로사르탄칼륨","amount":50,"unit":"mg"}],"dose":1,"doseUnit":"정","timesPerDay":1,"reason":"고혈압"}'

# 사진 OCR (실제 이미지 파일)
curl -s -X POST http://localhost:8080/api/medications/ocr -F "file=@/path/to/photo.jpg"
```

`npm run build`(front)와 `./gradlew compileJava`(backend)가 매 변경마다 통과하는 것도 함께 확인했습니다.
