# 메디바이스 프론트엔드 트러블슈팅

- 작성일: 2026-09-02 (최종 갱신 2026-09-03)
- 대상: `medivice-vue`. 1~9는 Mock 단계, 10~17은 실제 백엔드·DB·AI 연결 이후 발견된 문제(일부는 백엔드 코드가 원인이었지만 프론트 통합 테스트 중 발견되어 함께 기록)
- 기록 형식: 문제 → 원인 → 해결 → 확인

## 1. 초기 프로젝트가 Vue 기본 화면만 표시됨

### 문제

프로젝트를 실행하면 Vue 로고, `You did it!`, Home/About 화면만 표시되고 메디바이스 화면과 사용자 흐름이 존재하지 않았다.

### 원인

Vue 프로젝트를 생성한 직후의 기본 scaffold 상태였다. Router, Pinia 등 패키지는 포함되어 있었지만 실제 서비스용 Route, View, Store, 디자인 토큰이 작성되지 않았다.

### 해결

- `App.vue`에서 기본 Welcome 컴포넌트를 제거했다.
- 핸드오프의 화면 ID를 기준으로 View와 Route를 작성했다.
- 공통 `AppShell`과 서비스 컴포넌트를 분리했다.
- Pinia Store와 Mock API 경계를 추가했다.
- 기본 Vue 스타일을 서비스 디자인 토큰 기반 스타일로 교체했다.

### 확인

```sh
npm run lint
npm run build
```

Lint와 production build가 모두 성공했다.

## 2. 의존성 설치가 응답 없이 대기함

### 문제

처음 `npm install`을 실행했을 때 출력 없이 장시간 대기했다.

### 원인

작업 환경의 sandbox에서 외부 패키지 네트워크 접근이 제한되어 npm registry 요청을 완료하지 못했다.

### 해결

기존 설치 프로세스를 중지하고 승인된 외부 네트워크 환경에서 `npm install`을 다시 실행했다.

### 확인

```text
added 256 packages
audited 257 packages
found 0 vulnerabilities
```

`package-lock.json`이 생성된 뒤 다른 컴퓨터에서는 재현 가능한 설치를 위해 다음 명령어를 사용한다.

```sh
npm ci
```

## 3. ESLint에서 사용하지 않는 `props` 오류 발생

### 문제

첫 Lint 실행에서 다음 오류가 발생했다.

```text
RegistrationView.vue: 'props' is assigned a value but never used
ReportView.vue: 'props' is assigned a value but never used
```

### 원인

두 컴포넌트에서 `const props = defineProps(...)`로 선언했지만 Template에서는 `mode`를 직접 사용했다. Script 영역에서 `props` 변수를 참조하지 않아 `no-unused-vars` 규칙에 걸렸다.

### 해결

Vue 3.5의 반응형 Props 구조 분해 방식을 사용했다.

```js
const { mode } = defineProps({
  mode: { type: String, default: 'upload' },
})
```

### 확인

```sh
npm run lint
```

Oxlint와 ESLint 모두 오류 0건을 확인했다.

## 4. Node 단독 Store 테스트에서 `@` 별칭 해석 실패

### 문제

Pinia Store의 등록·삭제·스냅샷 흐름을 Node에서 직접 실행했을 때 다음 오류가 발생했다.

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@/api'
```

### 원인

`@` 별칭은 `vite.config.js`에서 `src`로 연결되는 Vite 전용 설정이다. Vite를 거치지 않는 순수 Node ESM 실행은 해당 별칭을 알지 못한다.

### 해결

Store에서 Mock API를 가져오는 경로를 상대경로로 변경했다.

```js
import { analyzeMedications, getDashboard, requestReport } from '../api/mockClient.js'
```

Vue 컴포넌트에서는 Vite가 처리하므로 기존 `@` 별칭을 유지했다.

### 확인

Node 상태 테스트에서 다음 흐름이 모두 통과했다.

- 초기 복용 항목 6건 로드
- 수기 항목 등록 후 7건
- 비타민D 합계 `3,400 IU` 재계산
- 증상 기록에 7건의 복용 스냅샷 저장
- 등록 항목 삭제 후 6건 복귀
- 비타민D 합계 `2,400 IU` 재계산
- 보고서 상태 `COMPLETED`

## 5. sandbox에서 Vite 개발 서버 실행 실패

### 문제

다음 명령어로 개발 서버를 실행했을 때 오류가 발생했다.

```sh
npm run dev -- --host 127.0.0.1 --port 4173
```

```text
Error: listen EPERM: operation not permitted 127.0.0.1:4173
```

### 원인

작업 환경의 sandbox가 로컬 TCP 포트 바인딩을 제한했다. Vue 또는 Vite 코드 자체의 문제는 아니었다.

### 해결

승인된 로컬 실행 환경에서 Vite 개발 서버를 다시 실행했다.

### 확인

Vite가 정상적으로 기동되고 로컬 접속 주소를 출력하는 것을 확인했다.

일반 개발 컴퓨터에서는 별도 조치 없이 다음 명령어를 사용하면 된다.

```sh
npm run dev
```

## 6. 지정한 4173 포트가 이미 사용 중이었음

### 문제

Vite 서버를 4173 포트로 실행하려 했으나 다음 메시지가 출력되었다.

```text
Port 4173 is in use, trying another one...
Local: http://127.0.0.1:4174/
```

### 원인

앞서 정적 HTML 검토를 위해 실행했던 임시 Python HTTP 서버가 4173 포트를 사용하고 있었다.

### 해결

- Vite가 자동 선택한 4174 포트에서 검증을 계속했다.
- 검증을 마친 뒤 Python 서버와 Vite 서버 프로세스를 모두 종료했다.

### 확인

4173과 4174 포트에 남은 Listen 프로세스가 없음을 확인했다.

### 재발 시 확인

macOS 또는 Linux:

```sh
lsof -nP -iTCP:4173 -sTCP:LISTEN
```

프로세스가 본인이 실행한 개발 서버인지 확인한 뒤 해당 터미널에서 `Ctrl + C`로 종료한다. 확인하지 않은 다른 사용자의 프로세스를 임의로 종료하지 않는다.

## 7. sandbox 안의 `curl`에서 개발 서버에 연결되지 않음

### 문제

Vite 서버가 실행 중인데도 sandbox 내부에서 다음 오류가 발생했다.

```text
curl: (7) Failed to connect to 127.0.0.1
```

### 원인

Vite 서버는 승인된 외부 실행 영역에 있었고, 기본 sandbox의 네트워크 영역에서는 해당 서버에 접근할 수 없었다.

### 해결

서버와 동일한 승인 네트워크 영역에서 Route 응답 검사를 실행했다.

### 확인

다음 Route가 모두 HTTP 200으로 응답했다.

```text
/login
/signup
/onboarding
/onboarding/profile
/onboarding/medications
/main
/medilight
/medications
/main/register
/main/register-confirm
/main/register-manual
/main/symptom
/my
/my/profile
/my/account
/my/symptoms
/report/new
/report/latest
/unknown
```

`/unknown`은 SPA의 `index.html`을 반환한 뒤 Vue Router의 NotFound 화면으로 처리한다.

## 8. 자동 브라우저 시각 검토를 수행할 수 없음

### 문제

구현 화면을 브라우저에서 열어 스크린샷과 실제 클릭 흐름을 자동 확인하려 했지만 연결 가능한 브라우저가 없었다.

### 원인

작업 환경에 in-app Browser 또는 연결된 Chrome/Edge 인스턴스가 제공되지 않았다.

### 해결

브라우저 자동화 도구를 임의로 대체하지 않고 다음 검증을 수행했다.

- Vue Template production compile
- ESLint와 Oxlint
- Vite production build
- 개발 서버 Route 응답
- 메디라이트 계산 로직
- Pinia 등록·삭제·스냅샷·보고서 상태 흐름

### 남은 확인

다른 컴퓨터에서 다음 항목을 수동으로 확인해야 한다.

- 데스크톱에서 168px 좌측 레일과 페이지 콘텐츠 정렬
- 모바일에서 하단 내비게이션 전환
- 라이트·다크 모드 가독성
- 로그인부터 보고서 결과까지 전체 클릭 흐름
- 수기 등록 후 메디라이트 수치 변경
- 복용 항목 삭제 후 판정 변경
- 증상 저장 후 마이페이지 카드 표시
- 긴 제품명과 작은 화면에서 텍스트 잘림 여부
- 키보드 포커스와 색 이외의 상태 정보 제공 여부

## 9. Backend 규격이 없는 상태에서 화면 작업 필요

### 문제

Frontend 전체 화면은 필요하지만 Backend Endpoint, Request, Response, Status Code가 팀 공용 규격으로 확정되지 않았다.

### 원인

Frontend와 Backend의 병렬 작업 단계이며 실제 API 계약 전이었다.

### 해결

- `src/api/mockClient.js`에 화면과 Store가 의존하는 데이터 경계를 분리했다.
- `API_CONTRACT`에 예상 Method, Endpoint, 성공 Status Code를 기록했다.
- 화면은 Mock client를 호출하고 직접 Mock 데이터를 소유하지 않도록 구성했다.
- 규칙 버전을 `v0.3-demo`로 표시해 실제 의료 규칙과 구분했다.

### 확인

Mock 구현만으로 등록, 삭제, 재계산, 증상 저장, 보고서 상태 흐름이 동작한다.

### 실제 API 연결 시 주의

1. Backend 담당자 및 PM과 계약을 먼저 확정한다.
2. `src/api/mockClient.js`의 데이터 형태와 실제 응답을 비교한다.
3. Axios instance와 환경변수 기반 `baseURL`을 추가한다.
4. 화면 컴포넌트에서 직접 Endpoint를 호출하지 않는다.
5. 정상·오류 Status Code를 모두 테스트한다.
6. 로딩, 빈 상태, 재시도, 인증 만료를 확인한다.

## 10. Vite 개발 서버가 아예 뜨지 않음 (native binding 오류)

### 문제

`npm run dev`를 실행하면 서버가 뜨기도 전에 다음 오류로 즉시 종료됐다.

```text
Error: Cannot find native binding. npm has a bug related to optional dependencies
(https://github.com/npm/cli/issues/4828).
cause: Error: Cannot find module '@rolldown/binding-darwin-arm64/rolldown-binding.darwin-arm64.node'
```

### 원인

프로젝트를 압축해서 전달하는 과정에 `node_modules`가 통째로 포함됐는데, Vite 8이 쓰는 네이티브 번들러(`@rolldown/binding-*`)의 실제 바이너리(`.node`) 파일이 npm의 알려진 optional-dependency 버그로 인해 그 안에 빠져 있었다. 패키지 폴더(`package.json`, `README.md`)만 있고 정작 바이너리가 없는 상태였다.

### 해결

```sh
rm -rf node_modules package-lock.json
npm install
```

재설치 후 `node_modules/@rolldown/binding-darwin-arm64/rolldown-binding.darwin-arm64.node` 파일이 실제로 존재하는지 확인한다.

### 확인

```text
VITE v8.2.2  ready in 716 ms
➜  Local:   http://127.0.0.1:5173/
```

`curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:5173/` → `200`.

### 관련 파일

- `node_modules/` (재생성됨, 커밋 대상 아님)
- `package-lock.json`

## 11. 회원가입한 이름이 화면에 반영되지 않고 계속 목업 이름으로 보임

### 문제

회원가입 시 아이디를 `asdf`로 입력했는데 메인 화면에는 계속 `김민서`(목업 값)가 표시됐다.

### 재현 방법

1. `/signup`에서 아이디 `asdf`로 가입
2. `/main` 진입
3. 헤더에 `김민서 님의 복약 현황`으로 표시됨(기대값: `asdf`)

### 원인

`AuthView.vue`의 회원가입 처리 코드가 `store.updateProfile({ username, sex, birthDate })`만 호출했다. 그런데 화면들이 실제로 읽는 필드는 `user.name`이지 `user.username`이 아니었다 — `username`만 채우고 `name`은 그대로 목업 값으로 남아 있었다.

### 해결

`updateProfile` 호출에 `name: username.value`를 함께 넘기도록 수정했다(이 서비스는 실명을 따로 받지 않고 아이디를 표시 이름으로도 쓴다 — DB 스키마에 이름 컬럼 자체가 없다). 이후 백엔드 실연결 단계(문제 12)에서 근본적으로는 실제 로그인 사용자를 서버가 식별하도록 다시 고쳤다.

### 확인

가입 직후 헤더에 입력한 아이디가 그대로 표시됨을 확인.

### 관련 파일

- `src/views/AuthView.vue`
- `src/stores/medivice.js` (`updateProfile`)

## 12. 프론트가 실제 백엔드를 전혀 호출하지 않음

### 문제

등록·삭제·증상 기록이 전부 브라우저 메모리에서만 동작하고, 새로고침하면 사라졌다. "API가 안 되는 것 같다"는 리포트.

### 원인

스토어(`stores/medivice.js`)가 `src/api/mockClient.js`만 참조하고 있었다 — 실제 Spring 백엔드(`../src`)로 요청을 보내는 코드 자체가 없었다.

### 해결

- `src/api/client.js`를 신설해 `fetch` 기반으로 `/api/dashboard`, `/api/medications`(POST/DELETE), `/api/symptoms`, `/api/reports`, `/api/auth/*`, `/api/medications/ocr`을 실제로 호출하도록 구현.
- 스토어의 `loadDashboard`/`createMedication`/`removeMedication`/`addSymptom`/`createReport`/`signup`/`login`/`runOcr`을 전부 이 클라이언트를 쓰도록 교체.
- 촬영 등록(OCR) 확인 화면에서만 쓰던 로컬 전용 mock 경로(`upsertMedication`)는 이후 실제 OCR이 붙으면서(문제 14~15) 완전히 제거됨.

### 확인

실제 백엔드(`localhost:8080`)를 띄운 상태에서 등록 → 새로고침 → 데이터 유지까지 curl과 DB 조회로 확인.

### 관련 파일

- `src/api/client.js`
- `src/stores/medivice.js`

## 13. Pinia 스토어를 고칠 때마다 "store.xxx is not a function"

### 문제

`stores/medivice.js`에 `signup`/`login` 등 새 함수를 추가한 직후, 이미 열려 있던 화면에서 `store.signup is not a function` 오류가 났다.

### 원인

Vite HMR이 `setup` 스토어(`defineStore(id, () => {...})`) 파일을 교체해도, 컴포넌트가 이미 `useMediviceStore()`로 받아 둔 예전 스토어 인스턴스(옛 클로저)를 계속 들고 있었다. Pinia는 이 케이스를 자동으로 처리하지 않고, 공식 HMR 헬퍼를 직접 붙여야 한다.

### 해결

```js
import { acceptHMRUpdate, defineStore } from 'pinia'
// ...
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useMediviceStore, import.meta.hot))
}
```

### 확인

이후 스토어 파일을 여러 번 고쳐도 브라우저를 새로고침하지 않고 새 함수가 바로 인식됨을 Vite HMR 로그(`hmr update /src/stores/medivice.js`, 오류 없음)로 확인.

### 관련 파일

- `src/stores/medivice.js`

## 14. 한글 아이디로 로그인하면 이후 모든 요청이 실패

### 문제

한글 아이디(예: `수정`)로 가입/로그인은 됐는데, 그 직후 대시보드나 사진 등록 요청에서 다음 오류가 났다.

```text
Failed to execute 'fetch' on 'Window': Failed to read the 'headers' property from
'RequestInit': String contains non ISO-8859-1 code point.
```

### 원인

로그인한 사용자를 서버에 알리기 위해 모든 요청에 `X-Medivice-User: <아이디>` 헤더를 실었는데, **HTTP 헤더 값은 ISO-8859-1(라틴 문자)만 허용**한다. 한글은 이 범위 밖이라 브라우저가 `fetch()` 요청 자체를 만들다가 즉시 거부했다 — 네트워크에 나가보지도 못하고 실패해서, 겉으로는 "서버가 꺼져 있다"처럼 보였다(문제 16과 연결).

### 해결

- 프론트: 헤더에 넣기 전에 `encodeURIComponent(loginId)`로 퍼센트 인코딩.
- 백엔드(`CurrentUserFilter`): 헤더를 읽을 때 `URLDecoder.decode(value, UTF_8)`로 디코딩. 순수 ASCII 아이디는 `%` 시퀀스가 없어 그대로 통과하므로 기존 계정도 영향 없음.

### 확인

`curl -H "X-Medivice-User: $(python3 -c "import urllib.parse;print(urllib.parse.quote('수정'))")" http://localhost:8080/api/dashboard` → 해당 사용자 데이터 정상 응답.

### 관련 파일

- `src/api/client.js` (`encodeLoginIdHeader`)
- `../src/main/java/com/project/medivice/config/CurrentUserFilter.java`

## 15. OCR이 사진에 없는 약 이름을 지어냄 (환각)

### 문제

실제 처방약 사진(제품명 "케이캡정 50mg")을 올렸는데 인식 결과가 "케이프론정 50mg"으로 나왔다 — 비슷하게 생긴 실재하지 않는 이름으로 바뀜.

### 원인

비전 AI 프롬프트가 "읽을 수 있는 값만 채우라"고는 했지만, 글자가 흐릴 때 "그럴듯한 실제 약 이름으로 추측하지 말라"는 지시가 없었다. 모델이 불확실한 상황에서 있어 보이는 이름으로 채워 넣는(hallucination) 전형적인 실패 패턴.

### 해결

프롬프트에 다음을 명시적으로 추가했다: "정확히 안 보이면 보이는 부분만 쓰거나 null로 두어라. 틀린 이름보다 빈 값이 낫다." 같은 원칙을 성분명·함량에도 동일하게 적용.

### 확인

같은 사진으로 재시도 → "케이캡정 50mg"으로 정확히 인식, confidence는 0.8(중간 수준— 실제로 글자가 완전히 선명하지는 않았음을 정직하게 반영).

### 관련 파일

- `../src/main/java/com/project/medivice/ai/OpenAiClient.java` (`OCR_PROMPT`)

## 16. 약봉투 속 서로 다른 약 여러 개가 하나로 합쳐짐

### 문제

약봉투 사진 한 장(실제로는 서로 다른 약 4개가 각각 봉지에 담김) → 인식 결과가 4개 약 이름·성분을 전부 하나의 "제품"으로 뭉쳐서 반환.

### 원인

- API/프롬프트가 "약 한 개" 응답 구조였다. 모델이 "복합제(알약 하나에 성분이 여러 개)"와 "봉투 안에 서로 다른 약이 여러 개"를 구분하지 못하고 전부 `ingredients` 배열에 욱여넣었다.
- 응답 스키마(`OcrExtractionResult`)도 최상위가 배열이 아니라 객체 하나였다.

### 해결

- 백엔드 `AiClient.extractMedicationInfo`의 반환 타입을 `OcrExtractionResult` → `List<OcrExtractionResult>`로 변경.
- OpenAI 구조화 출력 스키마를 `OcrExtractionBatch(List<OcrExtractionResult> medications)`로 감싸, 사진에서 구분되는 약마다 배열 항목 하나씩 넣도록 프롬프트에 명시(진짜 복합제는 여전히 한 항목의 `ingredients`에 성분을 나열).
- 프론트(`RegistrationView.vue`, `stores/medivice.js`): `ocrDraft`(단수) → `ocrDrafts`(배열)로 변경. 확인 화면(SCR-REG-002)이 약마다 카드로 나뉘어 표시되고, 카드별 체크박스로 포함 여부를 고른 뒤 "선택한 N건 등록"으로 순서대로 저장하도록 재작성.

### 확인

같은 약봉투 사진으로 재시도 → 케이캡정·가스모틴에스정·레바미드정·거드액 4건이 각각 분리되어 반환됨을 curl로 확인.

### 관련 파일

- `../src/main/java/com/project/medivice/ai/AiClient.java`
- `../src/main/java/com/project/medivice/ai/OpenAiClient.java`
- `../src/main/java/com/project/medivice/service/OcrService.java`
- `src/stores/medivice.js`
- `src/views/RegistrationView.vue`

## 17. 오류 메시지가 뭉뚱그려져서 진짜 원인을 가림

### 문제

문제 14의 실제 원인(헤더 인코딩)이 있었는데도, 화면에는 항상 "백엔드 서버에 연결할 수 없습니다. 서버가 http://localhost:8080 에서 실행 중인지 확인해주세요."만 떴다. 서버는 실제로 정상 동작 중이라 디버깅이 겉돌았다.

### 원인

`client.js`의 `catch` 블록이 `fetch()`가 던지는 모든 예외(서버 다운, CORS 차단, 헤더 인코딩 오류 등 원인이 전혀 다른 것들)를 전부 같은 고정 문구로 뭉갰다.

### 해결

`catch`에서 브라우저가 실제로 준 `error.message`를 함께 포함하도록 수정.

```js
} catch (networkError) {
  const reason = networkError instanceof Error ? networkError.message : String(networkError)
  throw new Error(`백엔드 서버(${BASE_URL})에 연결하지 못했습니다: ${reason}`)
}
```

### 확인

이후 동일 상황에서 실제 원인 문자열("String contains non ISO-8859-1 code point")이 화면에 그대로 노출되어 즉시 원인을 특정할 수 있었다(문제 14 해결의 단서가 됨).

### 관련 파일

- `src/api/client.js`

## 18. 현재 확인 명령어

다른 컴퓨터에서 문제를 재현하거나 작업 상태를 확인할 때 다음 순서로 실행한다. 프론트만 띄우면 API 호출이 전부 실패하므로(문제 12), **백엔드를 먼저** 띄운다.

```sh
# 1) 백엔드 (프로젝트 루트에서)
cd ../
PGHOST=127.0.0.1 PGPORT=5544 PGDATABASE=medivice_db PGUSER=<db-user> PGPASSWORD= \
MEDIVICE_AI_PROVIDER=mock \
./gradlew bootRun

# 2) 프론트 (front/ 에서, 새 터미널)
node --version   # 20.19 이상 또는 22.12 이상
npm --version
npm ci
npm run lint
npm run build
npm run dev
```

`curl -s http://localhost:8080/api/dashboard`가 JSON을 돌려주면 백엔드가 정상이다. 사진 등록(OCR)까지 실제로 확인하려면 `MEDIVICE_AI_PROVIDER=openai`와 `OPENAI_API_KEY`를 추가로 설정한다(문제 15·16 참고).

## 19. 문제 보고 시 기록 양식

새로운 문제가 생기면 이 문서에 다음 형식으로 추가한다.

```md
## 문제 제목

### 문제
사용자가 본 증상과 오류 메시지

### 재현 방법
1. 실행 조건
2. 수행한 행동
3. 실제 결과

### 원인
확인된 기술적 원인

### 해결
변경한 파일과 코드 또는 설정

### 확인
수행한 테스트와 정상 결과

### 관련 파일
- `src/...`
```

## 20. 백엔드 미실행 상태에서 로그인과 UI 동작 확인 불가

### 문제

프론트 개발 서버는 실행되지만 로그인 시 `http://localhost:8080` 연결 실패가 발생해 대시보드와 주요 UI 흐름을 확인할 수 없었다.

### 재현 방법

1. Spring Boot 백엔드를 실행하지 않는다.
2. Vue 로그인 화면에서 아이디와 비밀번호를 입력한다.
3. 실제 API 요청이 실패하고 다음 화면으로 이동하지 못한다.

### 원인

`src/stores/medivice.js`가 `src/api/client.js`를 직접 사용해 모든 기능이 실제 백엔드 실행 여부에 의존했다. 기존 `mockClient.js`도 대시보드와 보고서만 제공해 로그인·등록·삭제 등 End-to-End 화면 흐름을 대체할 수 없었다.

### 해결

- `VITE_USE_MOCK_API`에 따라 실제·Mock 구현체를 선택하는 `src/api/adapter.js`를 추가했다.
- 선택된 구현체만 `import()`로 지연 로드해 production 번들에 Mock 데이터가 포함되지 않도록 분리했다.
- `mockClient.js`에 실제 API와 같은 함수명·응답 구조·성공 Status Code를 가진 로그인, 회원가입, 조회, 등록, 삭제, 증상, OCR 기능을 추가했다.
- `.env.development.local`에서만 Mock을 활성화하고, 어댑터가 `DEV` 모드도 함께 확인해 production build에서는 항상 실제 API를 사용하도록 했다.
- 로그인 화면에 Mock 모드와 테스트 계정을 표시해 실제 인증으로 오해하지 않게 했다.
- 정적 검사 중 발견된 `client.js`의 불필요한 객체 spread fallback을 동일 의미로 정리했다.
- 네트워크 오류를 사용자 메시지로 감쌀 때 `{ cause: networkError }`를 함께 전달해 원래 오류를 보존했다.
- 순차 등록 로직에 남아 있던 불필요한 ESLint 예외 주석을 제거했다.

### 확인

- 개발 서버에서 `VITE_USE_MOCK_API=true`가 주입되고 `/login`, `/main`이 각각 HTTP 200을 반환함을 확인했다.
- Mock 함수를 직접 호출해 로그인 200, 대시보드 200, 복용 항목 등록 201, 증상 기록 201, 삭제 완료를 확인했다.
- Oxlint 0건, ESLint 0건, Prettier 검사 통과, `npm run build` 성공을 확인했다.
- production `dist`에서 `mock-medication`, `mock-symptom`, `Mock OCR 결과`, `v0.3-demo` 문자열이 없음을 확인해 Mock 데이터가 배포 번들에서 제외됨을 검증했다.

### 관련 파일

- `.env.example`
- `.env.development.local` (개인 개발 설정, 커밋 제외)
- `src/api/adapter.js`
- `src/api/client.js`
- `src/api/mockClient.js`
- `src/stores/medivice.js`
- `src/views/AuthView.vue`
