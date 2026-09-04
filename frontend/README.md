# 메디바이스 프론트엔드

영양제·상비약·처방약을 성분 기준으로 정리하고, 중복·상한 초과를 메디라이트(신호등)로 보여주는 Vue 3 앱입니다. 실제 Spring 백엔드(`../src`)와 실제 DB(식약처 DUR 데이터)에 연결되어 있고, 사진 등록은 실제 비전 AI(OpenAI)로 동작합니다 — 더 이상 Mock이 아닙니다.

## 실행

이 프론트는 백엔드가 `http://localhost:8080`에서 떠 있어야 정상 동작합니다. 백엔드 실행 방법은 `../TROUBLESHOOTING.md`(프로젝트 루트) 또는 아래 "백엔드 요약"을 참고하세요.

```sh
npm install
npm run dev
```

기본 개발 서버 주소는 `http://127.0.0.1:5173`. 품질 확인과 production build:

```sh
npm run lint
npm run build
```

### 백엔드 없이 UI 확인

실제 API 계약은 유지하면서 개발용 Mock API로 로그인·대시보드·등록·삭제·증상·OCR·보고서 화면을 확인할 수 있습니다.

```sh
# 개인 PC에서만 사용하는 .env.development.local (Git의 *.local 규칙으로 제외됨)
VITE_USE_MOCK_API=true

npm run dev
```

- 기본 Mock 아이디: `minseo_k`
- 비밀번호: 비어 있지 않은 값(예: `12345678`)
- 실제 API로 되돌릴 때: `.env.development.local`의 값을 `false`로 바꾸거나 파일을 제거
- production build에서는 Mock 설정과 관계없이 실제 API를 사용합니다.
- 커밋 가능한 설정 예시는 `.env.example`에 두며, 개인용 `.env.development.local`은 커밋하지 않습니다.

## 백엔드 요약 (참고용)

```sh
cd ../                       # 프로젝트 루트 (build.gradle이 있는 곳)
PGHOST=127.0.0.1 PGPORT=5544 PGDATABASE=medivice_db PGUSER=<db-user> PGPASSWORD= \
MEDIVICE_AI_PROVIDER=openai OPENAI_API_KEY=<your-key> \
./gradlew bootRun
```

- `MEDIVICE_AI_PROVIDER`를 생략하거나 `mock`으로 두면 실제 사진을 읽지 않고 고정된 표본 응답을 돌려줍니다(키 없이도 화면 흐름은 확인 가능).
- DB 접속 정보는 실제 로컬 Postgres 설정에 맞게 바꾸세요.

## 구현 범위

- **인증(UC1·2)**: 실제 회원가입·로그인. 비밀번호는 검증하지 않지만(Sprint 1 축소 범위) 아이디는 실제 `users` 테이블에 저장·조회됩니다.
- **핵심 E2E**: 약 등록(수기 또는 사진) → 실제 DB 저장 → 성분 합산·중복 판정(DB 뷰) → 메디라이트 갱신 → 복용 목록 표시.
- **사진 등록(UC8~12, EXT-1)**: 실제 비전 AI(OpenAI GPT-5)가 사진에서 약 정보를 읽습니다. 약봉투처럼 사진 한 장에 서로 다른 약이 여러 개 있으면 전부 각각 추출해 목록으로 보여주고, 사용자가 항목별로 확인·수정한 뒤 체크된 것만 한 번에 등록합니다.
- **수기 등록(UC13)**: 사진 없이 직접 입력. 복합제(성분 2개 이상)도 지원합니다.
- **삭제(UC14)**: 실제 DELETE. 소프트 삭제라 과거 증상 기록의 스냅샷은 유지됩니다.
- **증상 기록(UC20·21)**: 실제 DB 저장. 저장 시점의 복용 목록을 값 복사로 스냅샷합니다.
- **보고서(UC28·29)**: 실제 API 호출. AI가 숫자 근거(복용 항목 수·주의 건수 등)만 문장으로 풀어씁니다.
- **온보딩·마이페이지 특이사항**: 화면 흐름은 있지만 저장 API는 아직 없습니다(로컬 상태만, 알려진 gap — 아래 참고).

## 구조

```text
src/
├── api/client.js            # 실제 백엔드 호출 클라이언트 (X-Medivice-User 헤더로 사용자 식별)
├── api/mockClient.js        # 사용되지 않음(레거시) — 촬영 등록 Mock 데모용으로만 남아 있었으나 현재 미사용
├── assets/tokens.css        # 디자인 토큰
├── components/              # 신호등, 배너, 복약 행, 브랜드 공통 UI
├── layouts/AppShell.vue     # 좌측 레일, 상단바, 페이지 슬롯
├── router/index.js          # 화면 ID에 대응하는 lazy route
├── stores/medivice.js       # Pinia 상태와 인증·등록·OCR·삭제·기록·보고서 흐름
└── views/                   # 화면 단위 Vue 컴포넌트
```

세부 로직 흐름(요청이 프론트→백엔드→DB/AI로 어떻게 오가는지)은 `기능별_로직도.md`를 보세요.

## 실제 API 계약

`src/api/client.js`가 백엔드 호출을 전담합니다. 모든 요청에 `X-Medivice-User` 헤더(로그인 아이디, `encodeURIComponent`로 인코딩)를 실어 보내 서버가 누구의 요청인지 식별합니다 — 세션·토큰은 아직 없습니다(Sprint 1 축소 범위).

| Method | Endpoint | 성공 코드 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 201 | 회원가입(이미 있는 아이디면 그대로 이어서 씀) |
| POST | `/api/auth/login` | 200 | 로그인(존재하지 않는 아이디면 404) |
| GET | `/api/dashboard` | 200 | 사용자·복용 목록·메디라이트 초기 조회 |
| GET | `/api/medilight` | 200 | 규칙 판정 및 근거 조회 |
| POST | `/api/medications` | 201 | 확인 완료 항목 등록(복합제 지원, `ingredients` 배열) |
| DELETE | `/api/medications/:id` | 204 | 복용 항목 삭제·재계산 |
| POST | `/api/medications/ocr` | 200 | 사진 인식(multipart, 응답은 약 목록 배열) |
| POST | `/api/symptoms` | 201 | 증상과 복용 목록 스냅샷 저장 |
| POST | `/api/reports` | 202 | 보고서 생성(동기 처리, 즉시 COMPLETED로 응답) |

## 미확정 정책

- 노랑·빨강 판정 임계값: DB의 실제 DUR 데이터·`ingredient_daily_limits` 기준을 그대로 사용(화면 시연용 샘플 아님)
- 처방약 상한 비교: 규칙 엔진(DB 뷰) 설계상 포함됨 — 제외 여부는 여전히 팀 논의 대상
- 필요 시 복용 항목: 일일 합산 방식 미확정
- 촬영 이미지: 원본을 서버에 저장하지 않음(추출 결과만 저장)
- 한·영 전환: UI 상태만 구현하고 전체 번역은 제외
- 가족 계정: 비활성화된 향후 기능으로 표시
- 온보딩/마이페이지의 특이사항(지병·알레르기·키·몸무게) 저장: 아직 API 없음, 로컬 상태만 반영

## 안전 원칙

- 메디라이트 색은 규칙 계산 결과(DB 뷰)만 사용합니다. AI는 색을 정하지 않습니다.
- AI가 만든 값(OCR 추출, 보고서 요약)은 점선 박스·`AI` 라벨과 신뢰도 배지로 규칙 결과와 시각적으로 분리합니다.
- OCR 결과는 사용자가 화면에서 확인·체크하기 전까지 저장되지 않습니다.
- OCR이 이름을 확신하지 못하면 지어내지 않고 빈 값 또는 낮은 신뢰도로 남깁니다(틀린 이름보다 빈 값이 낫다는 원칙).
- 초록은 `안전함`이 아니라 `현재 규칙에서 확인된 문제 없음`으로 표시합니다.
- 증상 기록과 약의 인과관계를 판정하지 않습니다.

## 문제 기록

전체 트러블슈팅 이력은 `TROUBLESHOOTING.md`를 보세요 — 초기 스캐폴딩 문제부터 실제 API 연결 후 발견된 버그(HTTP 헤더 인코딩, OCR 환각 등)까지 원인·해결·확인 순으로 정리되어 있습니다.
