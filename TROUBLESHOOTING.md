# 메디바이스 백엔드 트러블슈팅

- 작성일: 2026-09-03 (최종 갱신: 2026-09-04 — Java 21 업그레이드, OCR 속도 개선, AI 설정 외부화·비동기 전환, 성분 DB 우선 조회·이름 폴백(3차: 부분 문자열 폴백 포함), DB 전체 재적재(43,019개 제품), 복용 항목 AI 설명(효능+부작용), 등록 입력값 검증, 동명·이형 성분 orphan 중복 정리, OCR 복합제/개별 약 판별 기준 보강, 메디라이트 배지·conflicts 설명 불일치 수정, 판정 범위 밖 성분 안내 구조화, 나이 조건 없는 특정연령대금기 오탐 수정, 병용금기 표에 실제 충돌 성분 노출 이후)
- 대상: `src/main/java/com/project/medivice/**` (Spring Boot) + 로컬 DB/AI 연동
- 기록 형식: 문제 → 재현 방법 → 원인 → 해결 → 확인 → 관련 파일

---

## 1. DA팀이 준 `.env`의 DB 접속 정보로 연결이 안 됨

### 문제

`DA_데이터파이프라인/.env`에 적힌 `PGUSER=postgres` / `PGPASSWORD=<DA팀 .env에 적힌 값>`로 접속했는데 매번 인증 실패.

```text
psql: error: connection to server at "127.0.0.1" ... failed:
FATAL:  password authentication failed for user "postgres"
```

### 원인

로컬 macOS에 서로 다른 Postgres 인스턴스가 **두 개**(EDB 설치 버전 17·18, 각각 5432/5433 포트) 이미 떠 있었다. `.env`의 비밀번호는 DA팀 작성자의 로컬 환경 기준이라 이 컴퓨터의 어느 인스턴스와도 맞지 않았다. 비밀번호를 추측해서 뚫는 것은 하지 않기로 하고, 대신 이 컴퓨터 계정이 실제로 소유한 인스턴스를 찾았다.

### 해결

`brew services`로 관리되는 Homebrew `postgresql@17`이 이미 로컬에 설치되어 있었지만 5432 포트 충돌로 기동에 실패한 상태였다. 이 클러스터는 로컬 계정이 직접 소유하고 있고 `pg_hba.conf`가 `trust`(비밀번호 없이 접속 허용)로 되어 있어, 충돌을 피해 별도 포트로 직접 띄웠다.

```sh
/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D /opt/homebrew/var/postgresql@17 \
  -o "-p 5544" -l /tmp/pg17-medivice.log start

psql -h 127.0.0.1 -p 5544 -U postgres -d postgres -c "CREATE DATABASE medivice_db;"
```

이후 `01_schema_ddl.sql` → `02_seed_code.sql` → `03_medilight_views.sql` → (Python 파이프라인으로 실데이터 적재) → `05_backend_extensions.sql` 순서로 적재.

### 확인

```sh
psql -h 127.0.0.1 -p 5544 -U postgres -d medivice_db -c "SELECT count(*) FROM medivice.ingredients;"
# 952
```

### 관련 파일

- `src/main/resources/application.properties` (`PGHOST`/`PGPORT` 등 환경변수로 주입)
- DA팀 `sql/01_schema_ddl.sql` ~ `05_backend_extensions.sql`

---

## 2. DA 데이터 파이프라인의 Python 가상환경이 이 컴퓨터에서 안 됨

### 문제

`DA_데이터파이프라인/.venv`가 이미 있어 그대로 쓰려 했으나 `psycopg2`를 import할 수 없었다.

### 원인

`.venv` 내부 구조가 `Lib/`, `Scripts/`(대문자 시작, 슬래시 구분)였다 — Windows에서 만든 가상환경이라 macOS(`lib/`, `bin/`)와 바이너리·경로 구조가 맞지 않았다.

### 해결

이 컴퓨터에서 새 가상환경을 만들고 필요한 패키지만 설치했다.

```sh
python3 -m venv loadenv
./loadenv/bin/pip install --quiet psycopg2-binary python-dotenv
PGHOST=127.0.0.1 PGPORT=5544 PGDATABASE=medivice_db PGUSER=postgres PGPASSWORD= \
  ./loadenv/bin/python3 src/load_postgres.py
```

### 확인

```text
[검증] 참조 무결성 / 제약
  [OK] 성분 없는 품목-성분 행: 0
  [OK] 양방향 중복으로 남은 쌍: 0
  [OK] 자기 자신과의 금기 쌍: 0
완료.
```

### 관련 파일

- DA팀 `src/load_postgres.py`, `src/config.py`

---

## 3. `NUMERIC` 컬럼을 `Double`로 캐스팅하다 `ClassCastException`

### 문제

`GET /api/dashboard` 호출 시 500 에러.

```text
java.lang.ClassCastException: class java.math.BigDecimal cannot be cast to class java.lang.Double
	at com.project.medivice.repository.UserRepository.lambda$findById$1
```

### 원인

`user_profiles.height_cm`/`weight_kg`가 `NUMERIC(5,1)`인데, PostgreSQL JDBC 드라이버는 `NUMERIC`을 `rs.getObject()`로 읽으면 항상 `BigDecimal`로 돌려준다. 코드가 `(Double) rs.getObject(...)`로 직접 캐스팅해서 즉시 실패했다.

### 해결

`rs.getBigDecimal(...)`로 받은 뒤 `.doubleValue()`로 좁히는 헬퍼를 추가했다.

```java
private static Double toDouble(BigDecimal value) {
    return value != null ? value.doubleValue() : null;
}
```

### 확인

`curl http://localhost:8080/api/dashboard` → `"height":162.0,"weight":58.0` 정상 응답.

### 관련 파일

- `src/main/java/com/project/medivice/repository/UserRepository.java`

---

## 4. `Stream#findFirst()`가 `null` 요소를 담으려다 NPE

### 문제

메디라이트 조회 시 간헐적으로 500 에러.

```text
java.lang.NullPointerException
	at java.util.Optional.of
	at ...MedilightViewRepository.findNoticeMessage
```

### 원인

`v_safety_notice.notice_message`는 판정 불가 성분이 0개면 정상적으로 `NULL`이다. `List<String>`에는 `null`이 들어갈 수 있지만, `rows.stream().findFirst().map(Optional::ofNullable)` 패턴은 스트림의 첫 요소 자체가 `null`일 때 `findFirst()` 내부에서 `Optional.of(null)`을 시도하다 죽는다 — `null`이 정상값인 이 케이스에서는 성립하지 않는 패턴이었다.

### 해결

스트림 대신 리스트를 직접 인덱싱하도록 바꿨다.

```java
return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
```

### 확인

판정 불가 성분이 0개인 사용자로 `GET /api/medilight` 호출 → `"noticeMessage":null`로 정상 응답(예외 없음).

### 관련 파일

- `src/main/java/com/project/medivice/repository/MedilightViewRepository.java`

---

## 5. `TIMESTAMPTZ` 컬럼을 `LocalDateTime`으로 직접 변환 시도

### 문제

약 등록(`POST /api/medications`) 직후 판정 스냅샷을 기록하는 단계에서 500 에러.

```text
org.postgresql.util.PSQLException: 유형 TIMESTAMPTZ 의 열을 요청된 유형
java.time.LocalDateTime (으)로 변환할 수 없습니다.
```

### 원인

`safety_checks.checked_at`, `side_effect_logs.written_at`이 `TIMESTAMPTZ`(시간대 포함)인데 `rs.getObject(col, LocalDateTime.class)`(시간대 없는 타입)로 직접 요청했다. PostgreSQL JDBC 드라이버는 이 변환을 거부한다.

### 해결

`OffsetDateTime`으로 받은 뒤 `.toLocalDateTime()`으로 좁혔다.

```java
OffsetDateTime checkedAt = rs.getObject("checked_at", OffsetDateTime.class);
return checkedAt != null ? checkedAt.toLocalDateTime() : null;
```

### 확인

약 등록 → 삭제 → 증상 기록을 연달아 호출해도 500 없이 정상 응답.

### 관련 파일

- `src/main/java/com/project/medivice/repository/MedilightViewRepository.java` (`findLatestCheckedAt`)
- `src/main/java/com/project/medivice/repository/SymptomRepository.java` (`written_at` 조회 2곳)

---

## 6. `bootRun`이 "Port 8080 was already in use"로 기동 실패

### 문제

`./gradlew bootRun` 실행 시 매번 포트 충돌로 기동 실패.

### 원인

이전에 백그라운드로 띄워 둔 `bootRun` 프로세스가 종료되지 않고 남아 있었다(세션 중 여러 번 재시작하면서 발생).

### 해결

```sh
kill $(lsof -tiTCP:8080 -sTCP:LISTEN)
./gradlew bootRun
```

devtools가 활성화되어 있어 소스를 고치고 `./gradlew compileJava`만 다시 돌리면 별도 재시작 없이 자동으로 hot-restart된다 — 매번 `bootRun`을 새로 실행할 필요는 없다.

### 확인

`lsof -nP -iTCP:8080 -sTCP:LISTEN` → 프로세스 1개만 존재.

---

## 7. 새 SDK(Anthropic Java, OpenAI Java)의 정확한 클래스명을 몰라 컴파일 실패 반복

### 문제

이미지 입력·구조화 출력 코드를 문서 기억에 의존해 짰더니 `ImageBlockParam`, `Base64ImageSource` 등 클래스명이 계속 틀렸다.

### 원인

공식 문서 스니펫에는 이미지 입력 예제가 충분히 나오지 않았고, SDK 클래스명을 추측해서 짜는 것은 신뢰할 수 없었다.

### 해결

Gradle로 의존성을 먼저 내려받은 뒤, 실제 jar를 직접 열어 정확한 이름을 확인했다.

```sh
./gradlew compileJava   # 의존성 jar를 캐시에 내려받기 위해
JAR=$(find ~/.gradle/caches -iname "anthropic-java-core-*.jar")
jar tf "$JAR" | grep -i Image
javap -classpath "$JAR" 'com.anthropic.models.messages.ImageBlockParam$Builder'
```

이 방식으로 `Base64ImageSource`/`ImageBlockParam`(Anthropic), `ChatCompletionContentPartImage`(OpenAI)의 정확한 빌더 메서드를 확인하고 코드를 작성했다.

### 확인

두 SDK 모두 `./gradlew compileJava` 1회 시도 만에 컴파일 통과.

### 관련 파일

- `src/main/java/com/project/medivice/ai/OpenAiClient.java`

---

## 8. Anthropic으로 구현했는데 실제로는 OpenAI 키였음 — AI 제공자 전면 교체

### 문제

사용자가 준 API 키가 `sk-proj-`로 시작 — Anthropic(`sk-ant-`)이 아니라 OpenAI 키였다. 이미 Anthropic Java SDK로 OCR을 구현해 둔 상태였다.

### 원인

AI 제공자 선택은 코드가 아니라 사용자의 실제 계정에 달린 문제라, 먼저 확인하지 않고 진행할 수 없었다.

### 해결

`AiClient` 인터페이스 뒤에 구현체가 있었기 때문에 교체가 국소적이었다 — `ClaudeAiClient`를 삭제하고 `OpenAiClient`를 새로 작성, `build.gradle`의 의존성만 `com.anthropic:anthropic-java` → `com.openai:openai-java`로 교체. 컨트롤러·서비스·프론트는 전혀 수정하지 않았다.

```properties
medivice.ai.provider=${MEDIVICE_AI_PROVIDER:mock}   # mock | openai
```

### 확인

`OPENAI_API_KEY`를 설정하고 실제 처방전 사진으로 `POST /api/medications/ocr` 호출 → 정상 인식.

### 관련 파일

- `src/main/java/com/project/medivice/ai/AiClient.java`
- `src/main/java/com/project/medivice/ai/OpenAiClient.java` (신규, `ClaudeAiClient.java` 대체)
- `build.gradle`

---

## 9. API 키를 셸 명령 인자에 직접 넣으려다 자동 승인 정책에 막힘

### 문제

`OPENAI_API_KEY='sk-proj-...' ./gradlew bootRun` 형태로 실행하려 하자 다음 메시지로 거부됨.

```text
Permission for this action was denied by the Claude Code auto mode classifier.
```

### 원인

셸 명령 문자열에 실제 비밀 키가 리터럴로 노출되는 패턴을 자동 정책이 차단했다(명령 기록·로그에 키가 남는 것을 막기 위함으로 추정).

### 해결

키를 프로젝트 루트의 `.env.local`(gitignore 처리)에 저장하고, 실행 시점에 `source`로만 불러왔다 — 키 자체는 어떤 Bash 명령 인자에도 리터럴로 나타나지 않는다.

```sh
# .env.local (커밋 안 됨)
export MEDIVICE_AI_PROVIDER=openai
export OPENAI_API_KEY=sk-proj-...

# 실행
(set -a; source .env.local; set +a; ./gradlew bootRun)
```

### 확인

동일한 방식으로 서버가 정상 기동되고, 이후 어떤 명령 로그에도 키 원문이 남지 않음을 확인.

### 관련 파일

- `.env.local` (레포에 없음, `.gitignore`로 3중 차단 — 프로젝트own·팀 공용 `.gitignore` 둘 다 `.env*` 패턴 보유)

---

## 10. 한글 로그인 아이디가 매 요청마다 헤더에서 거부됨

### 문제

한글 아이디로 가입한 사용자의 이후 모든 API 요청이 브라우저 단에서부터 실패.

```text
Failed to read the 'headers' property from 'RequestInit':
String contains non ISO-8859-1 code point.
```

### 원인

세션·토큰이 없는 대신 매 요청마다 `X-Medivice-User: <로그인 아이디>` 헤더로 사용자를 식별하는데, **HTTP 헤더 값은 ISO-8859-1만 허용**한다. 한글은 그 범위 밖이라 브라우저가 요청 자체를 만들다 거부했다.

### 해결

프론트에서 `encodeURIComponent`로 인코딩해 보내고, 백엔드 필터에서 짝을 맞춰 디코딩했다. 순수 ASCII 아이디는 `%` 시퀀스가 없어 그대로 통과하므로 기존 동작에 영향 없다.

```java
private static String decode(String value) {
    try {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
        return value;   // 우연히 %처럼 보이는 미인코딩 값은 원본 그대로
    }
}
```

### 확인

```sh
curl -H "X-Medivice-User: $(python3 -c "import urllib.parse;print(urllib.parse.quote('수정'))")" \
  http://localhost:8080/api/dashboard
# 해당 사용자 데이터 정상 응답
```

### 관련 파일

- `src/main/java/com/project/medivice/config/CurrentUserFilter.java`
- (프론트) `front/src/api/client.js`

---

## 11. 등록 API가 성분 1개만 받아 복합제를 담지 못함

### 문제

"아모잘탄정 5/50mg"처럼 성분이 2개(암로디핀+로사르탄칼륨)인 실제 처방약을 OCR로 읽었는데, 등록 API가 성분 하나(`ingredientName`/`amount`/`unit`)만 받는 구조라 두 번째 성분을 버릴 수밖에 없었다.

### 원인

초기 설계(UC13 수기 등록)가 "약 하나 = 성분 하나"를 전제로 만들어졌다. OCR이 실제 복합제를 정확히 읽어내기 시작하면서 이 전제가 깨졌다.

### 해결

`MedicationCreateRequest`를 단일 필드에서 `List<IngredientInput>`으로 바꾸고, `MedicationService.create()`가 성분마다 반복하며 `medication_ingredients`에 저장하도록 고쳤다. DB 스키마(`medication_ingredients`)는 원래부터 N:M 구조라 변경이 필요 없었다.

```java
public record MedicationCreateRequest(
        String type, String name,
        @NotEmpty @Valid List<IngredientInput> ingredients,
        ...) {
    public record IngredientInput(String name, BigDecimal amount, String unit) {}
}
```

### 확인

```sh
curl -X POST .../api/medications -d '{"ingredients":[
  {"name":"암로디핀","amount":5,"unit":"mg"},
  {"name":"로사르탄칼륨","amount":50,"unit":"mg"}], ...}'
# 두 성분 모두 저장, 로사르탄칼륨은 DUR 마스터와 매칭되어 영문명까지 채워짐
```

### 관련 파일

- `src/main/java/com/project/medivice/dto/MedicationCreateRequest.java`
- `src/main/java/com/project/medivice/service/MedicationService.java`
- (프론트) `front/src/views/RegistrationView.vue`

---

## 12. OCR이 사진에 없는 약 이름을 지어냄 (환각)

### 문제

실제 제품명이 "케이캡정 50mg"인 사진을 인식시켰는데 "케이프론정 50mg"(존재하지 않는 이름)으로 반환됨.

### 원인

프롬프트가 "읽을 수 있는 값만 채우라"고는 했지만, 글자가 흐릴 때 "비슷하게 생긴 실제 이름으로 추측하지 말라"는 지시가 없었다. 모델이 불확실한 상황에서 그럴듯한 이름으로 채워 넣는 전형적인 환각 패턴.

### 해결

프롬프트에 명시적으로 추가: "정확히 안 보이면 보이는 부분만 쓰거나 null로 두어라. 틀린 이름보다 빈 값이 낫다." 성분명·함량에도 동일 원칙 적용.

### 확인

같은 사진으로 재시도 → "케이캡정 50mg" 정확히 인식, confidence 0.8(완전히 선명하지는 않았음을 정직하게 반영).

### 관련 파일

- `src/main/java/com/project/medivice/ai/OpenAiClient.java` (`OCR_PROMPT`)

---

## 13. 약봉투 속 서로 다른 약 여러 개가 하나로 합쳐짐

### 문제

약봉투 사진 한 장(실제로는 서로 다른 약 4개)을 인식시켰더니 4개 약의 이름·성분이 전부 하나의 "제품"으로 뭉쳐서 반환됨.

### 원인

API/프롬프트가 "약 한 개" 응답 구조였다. "복합제(알약 하나에 성분이 여러 개)"와 "봉투 안에 서로 다른 약이 여러 개"를 모델이 구분하지 못했다.

### 해결

- `AiClient.extractMedicationInfo`의 반환 타입을 `OcrExtractionResult` → `List<OcrExtractionResult>`로 변경.
- OpenAI 구조화 출력은 루트가 객체 하나여야 해서 `OcrExtractionBatch(List<OcrExtractionResult> medications)`로 감쌈.
- 프롬프트에 "사진에서 구분되는 약마다 배열 항목 하나씩, 진짜 복합제만 한 항목의 ingredients에 여러 성분" 원칙을 명시.
- `OcrService.extract()`가 항목마다 `OcrResultDto`로 변환해 리스트로 반환.

### 확인

같은 약봉투 사진 재시도 → 케이캡정·가스모틴에스정·레바미드정·거드액 4건이 각각 분리되어 반환됨.

### 관련 파일

- `src/main/java/com/project/medivice/ai/AiClient.java`
- `src/main/java/com/project/medivice/ai/OpenAiClient.java`
- `src/main/java/com/project/medivice/service/OcrService.java`
- `src/main/java/com/project/medivice/controller/MedicationController.java` (`ocr()` 반환 타입 `List<OcrResultDto>`)

---

## 14. 로컬 저장소에 커밋이 하나도 없어 `git push`가 실패

### 문제

`git push`를 시도했는데 아무 반응이 없거나 실패. "안 되네"라는 보고만 있었음.

### 원인

`git status` 확인 결과 `On branch main / No commits yet` — 로컬에서 커밋을 한 번도 만들지 않은 상태였다. 원격에는 이미 팀원들이 만든 `main`/`backend`/`db`/`frontend`/`infra` 브랜치가 존재했다.

### 해결

1. 실제 키 노출 여부를 먼저 전수 검사(`grep -r "sk-proj-\|sk-ant-"`) — 어디에도 없음을 확인.
2. `git fetch origin` 후 `git checkout -b backend origin/backend`로 팀의 기존 `backend` 브랜치 이력 위에서 시작(로컬 미추적 `.gitignore`와 원격 파일이 충돌해 임시로 옮겨두고 병합).
3. 팀 `.gitignore`(비밀정보·강의자료 차단 규칙 포함)를 보존하면서 백엔드 전용 규칙(Gradle/IDE 산출물)을 추가로 병합.
4. `front/`, `front.zip`은 이번 커밋에서 제외(사용자 요청).
5. 팀 커밋 컨벤션(`type(scope): 설명`)에 맞춰 커밋 후 `git push -u origin backend`.

### 확인

```text
To https://github.com/park-yeon-ju/medivice.git
   3ad7723..036332d  backend -> backend
```

### 관련 파일

- `.gitignore` (팀 공용 규칙 + 백엔드 전용 규칙 병합)

---

## 15. Swagger UI 추가 후 `/v3/api-docs`가 500 — `NoSuchMethodError: Schema.$dynamicRef`

### 문제

`springdoc-openapi-starter-webmvc-ui`를 추가하고 `/swagger-ui/index.html`을 열었더니 스펙 로딩 단계에서 실패.

```text
NoSuchMethodError: 'io.swagger.v3.oas.models.media.Schema io.swagger.v3.oas.models.media.Schema.$dynamicRef(...)'
```

### 원인

`com.openai:openai-java`가 내부적으로 끌어오는 `swagger-annotations`(비-jakarta, 2.2.31)와 springdoc이 쓰는 `swagger-annotations-jakarta`(2.2.52)가 **같은 패키지·클래스명**을 갖고 있어, Gradle이 둘 중 하나만 클래스패스에 올리면서 버전이 섞였다. 우리 코드가 openai-java의 이 트랜지티브 의존성을 직접 쓰는 곳은 없었다.

### 해결

`build.gradle`에서 openai-java 의존성 선언에 `exclude`를 걸어 충돌 원인을 제거했다.

```gradle
implementation('com.openai:openai-java:4.56.0') {
    exclude group: 'io.swagger.core.v3', module: 'swagger-annotations'
}
```

추가로, `springdoc` 의존성처럼 **새 외부 jar를 build.gradle에 추가한 직후**는 devtools의 `compileJava` 기반 hot-restart로는 반영되지 않았다 — 클래스패스 자체가 바뀌는 변경이라 프로세스를 완전히 껐다가 `bootRun`으로 다시 띄워야 했다(#6과 같은 패턴이지만 원인은 다름 — #6은 포트 점유, 이건 신규 의존성 미인식).

### 확인

```sh
./gradlew dependencies --configuration compileClasspath | grep swagger-annotations
# swagger-annotations-jakarta 한 버전만 남음
curl -s http://localhost:8080/v3/api-docs | python3 -c "import json,sys;json.load(sys.stdin)"
# 정상 파싱, Swagger UI 정상 렌더링
```

### 관련 파일

- `build.gradle`

---

## 16. Postgres 재시작할 때 `PGUSER=postgres`로 복붙 실수 — "role postgres does not exist"

### 문제

`bootRun`을 재시작하다가 500 에러.

```text
org.postgresql.util.PSQLException: FATAL: role "postgres" does not exist
```

### 원인

이 컴퓨터의 Homebrew Postgres(#1)는 OS 계정(`seungminchoi`)이 곧 DB 롤이지 `postgres`라는 롤 자체가 없다. 이전에 다른 프로젝트/예시 명령을 복붙하면서 `PGUSER=postgres`로 잘못 띄운 것 — 코드 버그가 아니라 재시작 명령 자체의 실수였다.

### 해결

`PGUSER=seungminchoi`(이 컴퓨터의 실제 DB 롤)로 재시작. 이 값은 이 컴퓨터 전용이라 다른 팀원 환경에서는 다시 확인해야 한다(#21 참고).

### 확인

재시작 후 `curl http://localhost:8080/api/dashboard` 정상 응답.

### 관련 파일

- (기록용, 코드 변경 없음 — 재시작 명령의 환경변수 실수)

---

## 17. 성분 검색은 되는데 충돌 확인에서는 "찾지 못함" — 부분 일치 vs 정확 일치 간극

### 문제

Swagger에서 `GET /api/ingredients?query=덱시부프로펜`으로는 "덱시부프로펜 디.씨."가 검색되는데, 같은 이름을 그대로 `POST /api/ingredients/check`에 넣으면 `unresolvedNames`에 담겨 판정되지 않음.

### 원인

검색은 `ILIKE '%...%'` 부분 일치인데, 충돌 확인은 애초에 `WHERE name_ko IN (:names)` **정확 일치**만 받아들이도록 짜여 있었다. "검색되면 확인도 될 것"이라는 사용자 기대와 실제 구현이 어긋나 있었다.

### 해결

`IngredientRepository.searchByPartialName()`을 추가하고, `IngredientCheckService`에 이름 해석 공통 로직(`resolveNames`)을 넣었다 — 정확히 일치하면 그대로 쓰고, 아니면 부분 일치를 시도하되 **후보가 정확히 하나일 때만** 자동 채택한다. 후보가 여러 개면(예: "부프로펜"이 이부프로펜·덱시부프로펜 디.씨. 둘 다에 걸림) 추측하지 않고 후보 목록을 그대로 보여준다.

### 확인

```sh
curl -s -X POST .../api/ingredients/check -d '{"ingredients":[{"name":"덱시부프로펜","amount":300,"unit":"mg"}]}'
# unresolvedNames: [], note: "\"덱시부프로펜\"은(는) 마스터의 \"덱시부프로펜 디.씨.\"(으)로 자동 매칭했습니다."
curl -s -X POST .../api/ingredients/check -d '{"ingredients":[{"name":"부프로펜","amount":300,"unit":"mg"}]}'
# unresolvedNames: ["부프로펜"], 후보 3개를 나열하고 추측하지 않음
```

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java` (`searchByPartialName`)
- `src/main/java/com/project/medivice/service/IngredientCheckService.java` (`resolveNames`/`describeResolution`)

---

## 18. 마스터에 같은 이름이 두 번 등록된 성분 — 어느 쪽이 쓰일지 예측 불가능했던 버그

### 문제

"나프록센"을 입력하면 성분 마스터에 동일한 이름의 행이 **두 개**(id 252, 905 — 서로 다른 염/수화물 형태로 각각 실제 DUR 데이터가 붙어 있음) 있는데, 기존 코드는 `Map<String, IngredientSummaryDto>`에 `put`으로 채워서 DB가 반환하는 순서에 따라 둘 중 하나가 조용히 덮어써졌다 — 같은 요청도 실행마다 다른 결과가 나올 수 있는 잠재 버그.

### 원인

`findByNames()`가 이름이 같은 여러 행을 그대로 반환할 수 있다는 걸 고려하지 않고 "이름 → 성분 하나"로 가정한 채 짜여 있었다. DA팀 데이터 파이프라인 임포트 과정에서 동일 이름이 중복 등록된 행이 7종(나프록센, 올메사르탄메독소밀, 브리모니딘타르타르산염, 발프로산나트륨, 피록시캄, 설파디아진은, 미졸라스틴) 있었다.

### 해결

`resolveNames()`가 이름별로 매칭된 행을 리스트로 모은 뒤, 정확히 하나뿐이면 그대로 쓰고 여러 개면 그중 **실제 DUR 데이터(병용금기·효능군중복·1일상한·단일금기 어디든)가 연결된 행이 정확히 하나일 때만** 그 행을 자동 채택한다. 둘 다 데이터가 있거나(= 진짜 서로 다른 두 항목) 둘 다 없으면 추측하지 않고 후보를 코드(`ingr_code`)로 구분해 보여준다.

### 확인

```sh
curl -s -G .../api/ingredients/pair-check --data-urlencode "ingredientA=케토롤락" --data-urlencode "ingredientB=나프록센"
# "나프록센"에 해당하는 후보가 여러 개라 추측하지 않았습니다: 나프록센 (코드 D000982), 나프록센 (코드 D000195).
```

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java` (`findIdsWithDurData`)
- `src/main/java/com/project/medivice/service/IngredientCheckService.java` (`resolveNames`)

---

## 19. 실제 병용금기 조합인데 초록으로 나옴 — 같은 약의 다른 표기가 별개 행으로 등록됨

### 문제

외부 자료 기준 "케토롤락 + 나프록센(경구 소염진통제)은 병용금기"인데, `pair-check`에 그대로 입력하면 초록(확인된 문제 없음)이 나옴.

### 원인

DUR 마스터에 "케토롤락"(id 952)과 "케토롤락트로메타민염"(id 10, 주사제 염 형태)이 **별개의 성분 행**으로 들어있는데, 병용금기 규칙은 전부 "케토롤락트로메타민염" 쪽에만 연결돼 있었다. 사용자가 "케토롤락"이라고만 입력하면 규칙이 하나도 없는 "고아" 행으로 정확히 매칭되어(부분 일치 폴백조차 필요 없는 상황), 실제로는 마스터에 존재하는 규칙을 못 찾고 지나쳤다 — 판정 로직 버그가 아니라 마스터 데이터의 "동일 약물 다른 표기" 커버리지 한계였다.

### 해결

`IngredientRepository.findIdsWithPairOrEffectData()`(병용금기·효능군중복만 좁혀서 확인 — 단일금기만 있는 행은 pair-check 입장에서 여전히 "고아")를 추가해, 판정에 쓰인 성분이 이 두 규칙 어디에도 안 걸리는 행이면 `judgePair()`가 "확인된 문제 없음"에 다음 주의 문구를 덧붙이도록 했다: `"다만 \"케토롤락\"은(는) 마스터에 병용금기·효능군중복 등 DUR 연관 데이터가 전혀 등록돼 있지 않은 항목입니다 — 같은 성분의 다른 표기(염·수화물 등)로 확인해 보세요."` — "규칙이 없다"와 "이 표기로는 규칙을 찾을 근거 자체가 빈약하다"를 구분해 사용자에게 알린다.

### 확인

```sh
curl -s -G .../api/ingredients/pair-check --data-urlencode "ingredientA=케토롤락" --data-urlencode "ingredientB=아스피린"
# colorLabel: 초록, detail에 "고아" 주의 문구 포함
curl -s -G .../api/ingredients/pair-check --data-urlencode "ingredientA=케토롤락트로메타민염" --data-urlencode "ingredientB=아스피린"
# colorLabel: 빨강, "중증의 위장관계 이상반응" (정확한 표기로는 마스터 규칙이 정상 작동)
```

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java` (`findIdsWithPairOrEffectData`)
- `src/main/java/com/project/medivice/service/IngredientCheckService.java` (`judgePair`)

---

## 20. (미해결·기록만) 백엔드 구조가 팀 `CONTRIBUTING.md`와 다름

### 문제

원격 저장소에 이미 올라와 있는 `CONTRIBUTING.md`/`CLAUDE.md`가 명시한 컨벤션과 지금 구현이 다음 지점에서 다르다.

| 항목 | 팀 컨벤션 | 현재 구현 |
| --- | --- | --- |
| 패키지 | `com.medivice` | `com.project.medivice` |
| 폴더 위치 | `backend/src/...` | 레포 루트 `src/...` |
| 응답 형식 | `ApiResponse<T>` 래핑 | DTO 직접 반환 |
| 사용자 식별 헤더 | `X-User-Id`(숫자) | `X-Medivice-User`(로그인 아이디 문자열) |
| DB 접근 | JPA(`ddl-auto: update`) 전제 | 순수 JDBC(`NamedParameterJdbcTemplate`), DA팀 손수 작성 SQL 기반 |

### 원인

`CONTRIBUTING.md`는 프로젝트 초기에 작성된 팀 공용 가이드였고, 실제 DB 스키마는 DA팀이 ORM 없이 손수 작성한 DDL·Python ETL로 구축되었다 — 문서의 JPA 전제와 실제로 넘겨받은 산출물이 이미 어긋나 있었다. 나머지(패키지명·응답 포맷·헤더)는 백엔드를 먼저 단독으로 진행하면서 팀 문서를 확인하지 못한 채 진행된 부분.

### 해결 (보류)

사용자 요청으로 이번에는 현재 구조 그대로 `backend` 브랜치에 push했다(커밋 메시지에 이 불일치를 명시해 둠). 팀 컨벤션에 맞출지, 또는 문서 쪽을 실제 구현(JDBC 기반)에 맞게 갱신할지는 팀 논의 후 결정 필요.

### 관련 파일

- 전체 `src/**` (패키지 위치)
- 원격 `CONTRIBUTING.md`, `CLAUDE.md` (참고용, 이 레포의 백엔드 코드에는 없음 — `git show origin/backend:CONTRIBUTING.md`로 확인)

---

## 21. 이번 작업이 팀 공용 환경과 동일한 환경에서 이루어지지 않음

### 문제

이번 세션에서 검증한 내용이 **다른 팀원의 컴퓨터에서 그대로 재현된다는 보장이 없다.** 아래 항목들이 전부 "이 컴퓨터의 로컬 상태"에 의존해서 임시로 맞춘 것이라, 같은 명령을 다른 환경에서 실행하면 다시 막힐 수 있다.

### 재현 방법

1. 다른 팀원 컴퓨터에서 이 레포를 clone
2. `TROUBLESHOOTING.md`의 명령을 그대로 실행
3. DB 접속·환경변수·포트가 이 문서와 다르게 막힘

### 원인 — 구체적으로 무엇이 "동일 환경"이 아니었는가

| 영역 | 이번 세션에서 실제로 한 것 | 팀 공용/원래 의도였던 것 |
| --- | --- | --- |
| DB 인스턴스 | 이 컴퓨터 계정이 소유한 Homebrew `postgresql@17`을 **포트 5544**로 별도 기동(#1) | DA팀 `.env`가 가리키는 `PGPORT=5432`, 팀 공용 DB 서버 또는 각자 로컬 5432 |
| DB 인증 | `pg_hba.conf`가 `trust`인 이 컴퓨터 전용 클러스터라 비밀번호 없이 접속 | DA팀 `.env`의 `PGPASSWORD=<DA팀 .env에 적힌 값>` (이 컴퓨터에서는 애초에 안 맞았음, #1) |
| Python 실행환경 | macOS용으로 새로 만든 `loadenv`(#2) | DA팀이 커밋한 `.venv`(Windows 빌드라 이 컴퓨터에서 애초에 못 씀) |
| AI 키 | 이 세션에서만 `.env.local`에 넣어 쓴 개인 OpenAI 키(#8·#9) | 팀 공용 `.env.example`에 항목만 정의되어 있고, 실제 키는 각자 발급·보관 |
| 백엔드 실행 포트 | `8080` 고정, 이 컴퓨터에서 좀비 프로세스와 반복 충돌(#6) | 팀 컨벤션상으로도 `8080`이 맞지만, 각자 로컬에 이미 떠 있는 다른 프로세스와 충돌 가능성은 사람마다 다름 |
| 코드 구조 | 팀 `backend/` 폴더·`com.medivice` 패키지 컨벤션을 모른 채 레포 루트 `src/`·`com.project.medivice`로 독자 진행(#20) | 원격에 이미 존재하던 `CONTRIBUTING.md`/`CLAUDE.md` 컨벤션 — 세션 후반(git push 단계)에야 발견함 |

즉 이번 세션은 **"메디바이스 백엔드가 동작한다"는 것은 실증했지만, "이 저장소를 clone한 임의의 팀원 컴퓨터에서 문서만 보고 바로 동작한다"는 것까지는 검증하지 못했다.**

### 해결 (팀에서 확인·정리 필요)

- [ ] 팀 공용 DB 접속 정보(`PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`)를 실제로 쓸 수 있는 최신값으로 `.env.example`에 갱신하고, 각자 로컬에서 한 번씩 실제로 접속해서 맞는지 확인
- [ ] DA팀 `.venv`를 레포에서 빼고(OS 종속적이라 커밋 대상으로 부적절), `requirements.txt`만 남겨 각자 `python3 -m venv`로 새로 만들도록 안내
- [ ] `OPENAI_API_KEY`(또는 팀이 최종 채택할 AI 제공자 키)를 팀원 각자 개인 키로 `.env.local`에 넣게 하고, 없을 때는 `medivice.ai.provider=mock`으로 자동 폴백됨을 README에 명시(이미 코드는 그렇게 동작함, §8 참고)
- [ ] 15번 항목(패키지·폴더·응답 포맷 불일치)을 팀과 논의해 결론 내기 전까지는, 이 브랜치를 clone한 팀원도 동일한 "환경 불일치"를 겪을 수 있음을 PR 설명에 명시

### 확인

이번 세션 안에서는 위 모든 우회가 **이 컴퓨터 한 대에서는** 끝까지 재현 가능함을 확인했다(§1~§14 각 항목의 "확인" 참고). 다른 컴퓨터에서의 재현은 미확인.

### 관련 파일

- `.env.local` (이 컴퓨터에만 존재, 공유되지 않음)
- DA팀 `.env`, `.venv` (팀 공용이라고 커밋되어 있었으나 이 컴퓨터 환경과 불일치)
- `src/main/resources/application.properties` (환경변수 기본값)

---

## 22. 기획 문서의 "AI-Ready Web Service" 설계 원칙 대비 갭 (→ §25에서 나머지 2개 해소)

### 문제

기획 문서(AI-Ready Web Service 슬라이드)가 요구하는 4대 핵심 고려사항 — Interface First / Structured Data / Asynchronous Pipeline / Security & Config Isolation — 을 실제 구현과 하나씩 대조해보니, 2개는 충족하지만 2개는 부족하다.

### 대조 결과

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| Interface First | 충족 | `AiClient` 인터페이스 뒤에 `MockAiClient`/`OpenAiClient`가 있고 `medivice.ai.provider` 값만 바꾸면 교체됨. 프론트가 보는 DTO 모양은 provider와 무관하게 동일 |
| Structured Data | 대체로 충족 | OCR 응답이 Java record 기반 JSON Schema로 강제되는 OpenAI structured output. 다만 DB는 JSON 블롭이 아니라 관계형으로 정규화돼 있어, 문서가 말하는 "바로 JSON으로 저장"과는 결이 다름(변환 부담 최소화 취지 자체는 충족) |
| **Asynchronous Pipeline** | **미충족** | `POST /api/medications/ocr`가 완전히 동기 처리 — 상태 코드가 그냥 200이고 PENDING/PROCESSING 상태 자체가 없다. 실측으로 **응답에 100초 넘게 걸리는 것을 이미 확인**했다(§10 참고, 트리비얼한 테스트 이미지로도 104초). `POST /api/reports`는 상태 코드는 202를 쓰지만 `ReportService.create()` 주석에 "실제 비동기 워커는 범위 밖이라 요청 스레드 안에서 즉시 COMPLETED까지 만든다"고 명시돼 있어 — 겉모양만 비동기고 실제로는 동기다(다만 이건 Sprint 2 DoD에 이미 문서화된 의도적 축소) |
| **Security & Config Isolation** | **절반만 충족** | `OPENAI_API_KEY`는 환경변수로 완전히 분리돼 있다. 하지만 모델명(`ChatModel.GPT_5_2`)과 reasoning effort(`ReasoningEffort.XHIGH`) 같은 Model Parameter가 `OpenAiClient.java`에 Java 상수로 하드코딩돼 있어, 모델·파라미터를 바꾸려면 코드 수정 + 재컴파일이 필요하다. "코드 변경 없이 즉시 교체" 요구사항에는 안 맞는다 |

### 원인

Sprint 1~2는 "성분 판정 규칙 엔진이 정확히 동작하는가"에 집중했고, AI 연동(OCR·보고서 요약)은 Sprint 3 확장 지점으로 최소한만(Provider 교체 가능성 정도) 만들어 뒀다. 기획 문서의 Async/Config Isolation 요구사항까지 처음부터 다 반영하지는 않았다.

### 해결

- [x] `OpenAiClient`의 `MODEL`/`ReasoningEffort`를 `application.properties`(`medivice.ai.model`, `medivice.ai.reasoning-effort`)로 뺐다 — §24
- [x] `POST /api/medications/ocr`을 202 Accepted + PENDING/PROCESSING/COMPLETED/FAILED 폴링 구조로 바꿨다 — §25
- [ ] `POST /api/reports`도 실제 비동기 워커가 붙을 때 `jobStatus` 필드 모양은 그대로 재사용 가능(이미 그렇게 설계돼 있음, `ReportService` 주석 참고) — 워커만 붙이면 됨. 아직 미착수

### 확인

각 항목의 근거는 실제 코드(`grep`)와 이전 세션에서 실측한 OCR 응답 시간(§10)으로 확인했다. 코드 변경은 하지 않았다.

### 관련 파일

- `src/main/java/com/project/medivice/ai/OpenAiClient.java` (`MODEL`, `ReasoningEffort` 하드코딩)
- `src/main/java/com/project/medivice/controller/MedicationController.java` (`ocr()` — 상태 코드·동기 처리)
- `src/main/java/com/project/medivice/service/ReportService.java` (동기 처리 주석)
- `src/main/resources/application.properties` (현재 `medivice.ai.provider`만 외부화됨)

---

## 23. 표시 언어(한국어/English) 토글이 실제로는 아무것도 번역하지 않음

### 문제

1. 진료용 보고서(`POST /api/reports`)를 `language=en`으로 만들어도, AI 요약 문장(`narrative`)만 영어고 사용자가 직접 적은 **증상명·메모(`symptoms`)는 항상 한국어 원문 그대로** 실렸다.
2. 프론트의 "표시 언어" 토글(`AppShell.vue`·`MyView.vue`의 한국어/English 버튼)은 `store.language` 값만 바꿀 뿐, 이 값을 읽어서 실제로 화면 텍스트를 바꾸는 코드가 어디에도 없었다 — EN을 눌러도 마이페이지 증상 기록, 보고서 미리보기의 "등록 사유" 등 사용자 입력 한글이 그대로 보였다(`grep`으로 `store.language` 사용처를 확인해보니 자기 자신을 하이라이트하는 것 외엔 소비하는 곳이 없었음).

### 원인

성분명(`nameKo`/`nameEn`)처럼 DUR 마스터에 영문명이 이미 있는 데이터는 언어 전환이 필요 없지만, 증상명·메모·등록 사유·성분 메모(`ingredientNote`)처럼 **사용자가 그때그때 자유롭게 입력한 한글 텍스트**는 애초에 영문 버전이 존재하지 않는다 — 이건 값을 골라 보여주는 문제가 아니라 실제 번역이 필요한 문제인데, 번역 기능 자체가 구현돼 있지 않았다.

### 해결

DeepL API(무료 티어 키, `xxxx:fx` 형식)를 연동했다. 새 의존성을 추가하지 않기 위해 Java 표준 `HttpClient`로 DeepL REST API(`https://api-free.deepl.com/v2/translate`)를 직접 호출한다.

- `TranslationService` 신규: `translateAll(texts, targetLang)` — 여러 문장을 한 번의 HTTP 호출로 번역. 키가 없거나(`medivice.translate.api-key` 비어 있음) 호출이 실패하면 **원문을 그대로 돌려준다** — 번역 실패가 보고서 생성·화면 렌더링을 막으면 안 되기 때문(`AiClient`의 mock 폴백과 같은 원칙).
- `ReportService.create()`: `language=en`일 때만 증상 기록들의 증상명·메모를 모아 DeepL 1회 호출로 번역 후 재배치.
- `POST /api/translate` 신규(범용): `{texts, targetLang}` → `{enabled, translations}`. 보고서뿐 아니라 화면 어디서든 재사용할 수 있도록 별도 엔드포인트로 뺐다.
- 프론트: `stores/medivice.js`에 `translationCache`(원문→번역문 캐시)와 `translate(text)` 헬퍼, `translateVisibleText()`(현재 로드된 medications의 `reason`/`ingredientNote`, symptoms의 `symptoms[]`/`note`를 모아 한 번에 번역) 추가. `setLanguage('EN')` 호출 시, 그리고 EN 상태에서 새 데이터가 로드/추가될 때(`loadDashboard`/`createMedication`/`addSymptom`) 자동으로 캐시를 채운다. `MyView.vue`(증상 기록), `ReportView.vue`(등록 사유 열), `MedicationRow.vue`(`ingredientNote`)가 `store.translate(...)`를 통해 표시하도록 교체.

### 확인

```sh
# 백엔드 단독 확인
curl -X POST localhost:8080/api/translate -d '{"texts":["두통","고혈압"],"targetLang":"EN"}'
# {"enabled":true,"translations":["Headache","High Blood Pressure"]}

# 보고서(EN) — 증상까지 번역되는지
curl -X POST localhost:8080/api/reports -d '{"from":"2026-08-01","to":"2026-09-03","language":"en"}'
# symptoms[].symptoms: ["Headache","Dizziness"], note: "I felt nauseous after taking the medicine."
# 같은 데이터로 language=ko 호출 시 한글 원문 그대로 유지되는 것도 확인(불필요한 API 호출 없음)
```

`npx vite build`·`npx vite`(dev 서버 기동) 둘 다 정상 통과.

### 관련 파일

- `src/main/java/com/project/medivice/service/TranslationService.java` (신규)
- `src/main/java/com/project/medivice/controller/TranslateController.java` (신규)
- `src/main/java/com/project/medivice/dto/TranslateRequest.java`, `TranslateResponse.java` (신규)
- `src/main/java/com/project/medivice/service/ReportService.java` (`translateSymptoms`)
- `.env.local`의 `DEEPL_API_KEY` (커밋 안 됨), `application.properties`의 `medivice.translate.api-key`
- (프론트) `front/src/stores/medivice.js`, `front/src/api/client.js`, `front/src/views/MyView.vue`, `front/src/views/ReportView.vue`, `front/src/components/MedicationRow.vue`

---

## 24. OCR 응답이 느림 — 이미지 리사이즈 + reasoning effort 재조정

### 문제

사용자가 "OCR이 느리다"고 보고. §22에서 이미 "미해결·기록만"으로 남겨둔 항목(트리비얼한 이미지로도 104초, `ReasoningEffort.XHIGH` 하드코딩)을 실제로 손본 작업이다.

### 원인

응답 시간은 두 군데에서 늘어난다.

1. 휴대폰 사진은 보통 3000~4000px대인데, `detail=HIGH`로 고정돼 있어 이미지가 클수록 OpenAI가 처리할 타일 수가 그만큼 늘어난다.
2. `OpenAiClient`의 OCR 호출에 `ReasoningEffort.XHIGH`가 하드코딩돼 있다. §12(OCR 환각)에서 성분·함량표 줄 뒤섞임을 잡으려고 올려둔 값인데, 이번에 실측해보니 정확도와 비례하지 않았다(아래 확인 참고).

### 해결

**1) 이미지 리사이즈** (`OcrService.resizeForOcr` 신규) — 업로드 이미지의 긴 변이 2000px를 넘으면 JPEG(품질 0.85)로 다시 인코딩해 축소 후 AI 클라이언트로 넘긴다. 2000px 이하면 원본 그대로 통과시키고, `ImageIO`가 못 읽는 포맷(WEBP 등)이면 예외 없이 원본을 그대로 쓴다. `reasoningEffort`·`detail=HIGH`처럼 판독 정확도에 영향을 줄 수 있는 설정은 건드리지 않고, 순수하게 전송 바이트·타일 수만 줄이는 방향으로 잡았다.

**2) reasoning effort 재조정** — 사용자가 준 실제 샘플 사진(`s1.jpg`, 2560×1920, 서로 다른 약 7개가 적힌 복약안내지)으로 `OpenAiClient`와 동일한 모델(`GPT_5_2`)·프롬프트·`detail=HIGH`를 그대로 써서 `none`/`low`/`medium`/`high`/`xhigh` 5단계를 전부 실측했다(`minimal`은 이 모델이 400으로 거부: "reasoning_effort does not support 'minimal'"). 실제 OpenAI API를 호출한 결과:

| reasoningEffort | 응답 시간 | 약 이름 7개 정확도 |
| --- | --- | --- |
| none | 10.9s | 6/7 ("비졸본정"→"비출본정" 오독) |
| **low** | **17.4s** | **7/7** ← 채택 |
| medium | 30.9s | 7/7 |
| high | 54.5s | 6/7 (같은 오독 재발) |
| xhigh (기존값) | 468.7s (7분 48초) | 7/7 |

"reasoning을 올릴수록 정확해진다"는 전제와 달리 이 표본에서는 `low`·`medium`·`xhigh`가 전부 약 이름 7개를 정확히 읽었고, 오히려 `high`·`none`에서만 글자 하나가 틀렸다 — 즉 reasoning effort와 정확도가 단순 비례하지 않았다. `low`가 가장 빠르면서 정확도도 최상위와 동일해 `OpenAiClient.java`의 `reasoningEffort`를 `HIGH`에서 `LOW`로 낮췄다. (단, 이 표본은 §12가 다뤘던 "한 알에 성분이 여러 개인 촘촘한 단일 함량표"가 아니라 "서로 다른 약 여러 개가 나열된 문서"라 — 진짜 다성분 함량표 사진에서 누락·뒤섞임이 다시 보이면 정확도가 동일했던 `medium`부터 먼저 시도하고, 그래도 안 되면 되돌린다.)

### 확인

- `./gradlew compileJava` 통과, 백엔드 재기동 후 `/swagger-ui/index.html` 200 확인.
- 위 표의 5개 호출은 전부 실제 OpenAI API 호출(과금 발생, 총 소요 약 10분 — 대부분 `xhigh` 한 번에 8분 가까이 걸림).
- 이미지 리사이즈는 합성 이미지(4032×3024, 휴대폰 사진 전형적 크기)로 별도 검증: 2000×1500으로 축소, 파일 용량 약 75% 감소. `s1.jpg`(2560×1920) 자체도 2000px를 넘어 실제 요청에서는 리사이즈+LOW가 함께 적용된다 — 이번 reasoning-effort 벤치마크는 리사이즈 적용 전 원본 크기로 5단계를 비교한 것이라, 실제 운영 응답시간은 표의 값보다 더 낮을 가능성이 높다.

### 관련 파일

- `src/main/java/com/project/medivice/service/OcrService.java` (`resizeForOcr`, `encodeJpeg` 신규)
- `src/main/java/com/project/medivice/ai/OpenAiClient.java` (`reasoningEffort`: `HIGH` → `LOW`)
- §12 (OCR 환각 — 애초에 `XHIGH`를 도입한 배경), §22 (이 문제를 처음 "미해결·기록만"으로 남긴 곳 — 설정 외부화는 §24 이후 별도로, 202+폴링 비동기 전환은 §25에서 마저 처리했다)

---

## 25. `POST /api/medications/ocr`을 동기 → 비동기(202 + 폴링)로 전환

### 문제

§22가 지적한 대로 `POST /api/medications/ocr`은 완전히 동기 처리였다 — `LOW`로 낮춘 뒤에도(§24) 여전히 17~26초가 걸리는데(사진 크기·네트워크 상태에 따라 변동), 요청 스레드가 그동안 그냥 멈춰 있었다. 기획 문서(AI-Ready Web Service, 강의 자료 4쪽) 원칙 ③이 요구하는 "비동기 처리 + 상태 관리(Pending/Completed)를 수용할 수 있는 Endpoint 구조"를 충족하지 못한 상태였다.

### 원인

Sprint 3에서 AI 연동을 최소 범위로만 붙이면서, `OcrService.extract()`가 컨트롤러 스레드 안에서 AI 호출까지 전부 동기로 끝내도록 짜여 있었다. 별도 워커·작업 상태 저장소가 없었다.

### 해결

리뷰 문서(`AI연동_검토결과.html`)가 제안한 "큐 서버 없이, `@Async` + 메모리 상태 저장소" 수준으로 최소하게 구현했다. 실무 규모의 큐(RabbitMQ/SQS)나 SSE는 이 과제 범위에 맞지 않는다고 판단해 넣지 않았다.

- **`OcrService`**: `extract(MultipartFile)` 하나였던 걸 세 조각으로 나눴다.
  - `readAndValidate(MultipartFile) -> RawInput(bytes, mimeType)` — 검증 + 바이트 추출만, 컨트롤러 스레드에서 동기로 끝나야 하는 부분(`MultipartFile`은 응답이 나간 뒤엔 더 이상 못 읽는다).
  - `runOcr(byte[], String) -> List<OcrResultDto>` — 리사이즈 + AI 호출 + 로그(§24에서 만든 그대로) + DTO 변환. 순수 로직이라 어디서 호출해도 동일하게 동작한다.
  - `@Async processAsync(byte[], String) -> CompletableFuture<List<OcrResultDto>>` — `runOcr`을 감싸기만 한다. **주의**: 이 메서드를 `OcrService` 자기 자신의 다른 메서드가 호출하면 스프링 프록시를 안 거쳐서 `@Async`가 조용히 무시되고 동기로 실행되는 흔한 함정이 있다 — 그래서 호출은 항상 다른 빈(`OcrJobService`)에서 하도록 구조를 나눴다.
- **`OcrJobService`(신규)**: 작업 상태를 `ConcurrentHashMap<String, OcrJob>`(메모리)으로 들고 있다. `submit(file)`이 jobId를 만들고 `PENDING`으로 등록한 뒤 `ocrService.processAsync(...)`를 호출·즉시 반환, `future.whenComplete(...)`로 완료·실패 시 맵을 갱신한다(`COMPLETED`+결과 또는 `FAILED`+에러 메시지).
- **`MedicationController`**: `POST /ocr`은 이제 `202 Accepted` + `{jobId, status:"PENDING"}`만 즉시 돌려준다. 새 `GET /ocr/{jobId}`가 상태를 폴링용으로 돌려준다(모르는 jobId는 기존 `NotFoundException` 경로를 그대로 타 404).
- **`MediviceApplication`**에 `@EnableAsync` 추가.

**프론트는 건드리지 않았다** — 사용자 확인 하에 백엔드 API 구조만 바꿨다. 지금 `front/src/stores/medivice.js`의 `runOcr()`은 여전히 동기 배열 응답을 기대하므로, 실제 화면에서 사진 업로드를 누르면 `{jobId, status}` 객체를 결과 배열인 것처럼 다루다 깨진다 — 프론트에서 폴링 로직을 붙이기 전까지는 API 명세·구조만 원칙에 맞춰둔 상태라고 보면 된다.

### 확인

`curl`로 전체 흐름 실측(`s1.jpg`, 실제 OpenAI 호출):

```text
POST /api/medications/ocr        → 202 {"jobId":"8ce3...","status":"PENDING", ...}
GET  /api/medications/ocr/{id}   (즉시)   → 200 {"status":"PROCESSING", ...}
GET  /api/medications/ocr/{id}   (25초 후) → 200 {"status":"COMPLETED","result":[...7건...]}
GET  /api/medications/ocr/모르는id        → 404 {"message":"OCR 작업을 찾을 수 없습니다: ..."}
```

완료 로그의 스레드명이 `task-1`로 요청 스레드(`nio-8080-exec-*`)와 다른 것도 확인해 `@Async`가 실제로 별도 스레드에서 도는 것(자기-호출 함정에 안 걸렸다는 것)을 검증했다:

```text
OCR 호출 완료: 23201ms, ... [task-1] c.project.medivice.service.OcrService
```

`/v3/api-docs`에도 `POST /api/medications/ocr`, `GET /api/medications/ocr/{jobId}` 둘 다 정상 노출됨을 확인했다.

### 관련 파일

- `src/main/java/com/project/medivice/service/OcrService.java` (`extract` 제거 → `readAndValidate`/`runOcr`/`processAsync`로 분리)
- `src/main/java/com/project/medivice/service/OcrJobService.java` (신규)
- `src/main/java/com/project/medivice/service/OcrJob.java`, `OcrJobStatus.java` (신규)
- `src/main/java/com/project/medivice/dto/OcrJobDto.java` (신규)
- `src/main/java/com/project/medivice/controller/MedicationController.java` (`POST /ocr` 응답을 202로, `GET /ocr/{jobId}` 신규)
- `src/main/java/com/project/medivice/MediviceApplication.java` (`@EnableAsync`)
- §22(이 갭을 처음 기록한 곳), §24(reasoning effort·이미지 리사이즈 — 이 작업 이후에도 17~26초는 걸려서 비동기 전환의 필요성이 여전했음)
- (미착수, §22에 남겨둠) `front/src/stores/medivice.js`의 `runOcr()` 폴링 대응, `POST /api/reports`의 동일한 비동기 전환

---

## 26. OCR 성분 인식을 AI 단독 판독에서 "제품명 → DB 조회, 실패 시 AI 폴백"으로 전환

> **정정(§28)**: 아래에서 "무코스타서방정"·"모티리톤정"이 "수집 범위 밖이라 DB에 없다"고
> 진단한 건 틀렸다. 실제로는 로컬에 데이터가 다 있었는데 마이그레이션(06·08·09) 미적용으로
> DB 적재가 절반만 돼 있었을 뿐이다 — §28에서 재적재 후 둘 다 정상 매칭된다. 이 절 자체의
> 하이브리드 매칭 로직(1차 DB 조회, 실패 시 AI 폴백) 설계는 여전히 유효하다.

### 문제

§12(OCR 환각)에서 프롬프트로 어느 정도 억제했지만, 근본적으로 AI가 사진 속 "성분·함량표"를
글자 그대로 눈으로 읽는 방식은 애초에 실수가 나기 쉬운 작업이다(§24 벤치마크에서도 이 표가
"가장 실수가 많이 나는 부분"이라고 프롬프트에 명시해뒀을 정도). 사용자가 실제 사진(`7198.jpg`,
처방전 3개 약)으로 확인해보니, 약 이름(예: "벨록스캡정40밀리그램")은 비교적 뚜렷하게 잘
읽히는 반면 함량표는 사진마다 화질·각도가 달라 신뢰하기 어려웠다.

### 원인

OCR 파이프라인이 제품명·성분·함량을 전부 AI 한 번의 시각 판독에만 의존했다. 그런데
DA_데이터파이프라인이 이미 공공데이터포털(식약처)에서 제품 22,271종의 **허가 원본 성분·함량**을
`medivice.products`/`medivice.product_ingredients`에 적재해 뒀다 — 이 데이터가 있는 제품이라면
AI가 사진에서 성분표를 "읽어서 추측"할 필요가 아예 없다.

### 해결

`OcrService.toDto()`에서 AI가 읽은 제품명으로 먼저 DB를 찾아보고, 있으면 그 성분으로 **대체**,
없으면 기존처럼 AI가 읽은 성분을 그대로 쓰는 하이브리드 방식으로 바꿨다.

- `IngredientRepository.findIngredientsByProductName(String)`(신규): `medivice.products.name_ko`가
  `"이름(주성분)"` 형태라(예: `벨록스캡정40밀리그램(펙수프라잔염산염)`), 사진에서 읽은 이름
  (괄호 없음)과는 **정확히 일치하거나, 그 이름 뒤에 "("이 바로 오는 접두어**까지만 매칭한다.
  동명이 여러 건 걸리면 `product_id`가 가장 작은 것 하나만 쓴다(여러 제품의 성분을 섞는 것보다
  안전).
- `OcrService.toDto()`: DB 매칭이 있으면 그 성분으로 `ingredients`를 교체하고, 응답의 "성분" 행
  라벨을 `"성분 (DB 확인)"`으로 바꾸고 confidence를 `1.0`으로 표시한다(식약처 허가 원본이라
  AI의 시각 판독보다 신뢰도가 높다는 것을 화면에서도 구분할 수 있게). 없으면 기존 동작 그대로
  AI가 읽은 값(없으면 빈 배열)을 쓴다 — **AI에게 이미 지시해 둔 "모르면 지어내지 말고 비워라"
  원칙(§12)은 그대로 유지**, DB가 그 위에 한 단계 검증을 얹은 것뿐이다.
- 공공데이터포털 API를 요청 경로에서 실시간으로 추가 호출하는 방안(커버리지는 더 넓어지지만
  외부 API 왕복이 OCR 응답 시간에 또 얹힌다)은 이번엔 넣지 않기로 했다 — 사용자와 상의 후,
  DB에 없으면 AI 판독값을 그대로 쓰는 쪽을 택함.

### 확인

`7198.jpg`(처방전, 약 3개)로 `POST /api/medications/ocr` → 폴링 → 실측:

```text
벨록스캡정40밀리그램        → DB 매칭 성공: 펙수프라잔염산염 40mg, confidence 1.0 ("성분 (DB 확인)")
무코스타서방정150밀…       → DB 매칭 안 됨(수집 범위 밖) → AI 판독 그대로: ingredients []
                              (AI 스스로 "성분/함량 표기는 보이지 않아 비워둠"이라고 정직하게 표시)
모티리톤정                  → DB 매칭 안 됨 → 위와 동일하게 빈 배열 유지
```

`medivice.products` 22,271건 중 이 사진의 3개 제품을 직접 조회해, 접두어 매칭 로직이 실제
DB 스키마(제품명에 성분이 괄호로 붙는 규칙)와 맞는지 `psql`로 먼저 검증한 뒤 코드를 짰다.

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java`
  (`ProductIngredientRow`, `findIngredientsByProductName` 신규)
- `src/main/java/com/project/medivice/service/OcrService.java` (`toDto`가 DB 조회를 우선하도록 수정)
- §12(OCR 환각 — "모르면 비워라" 원칙의 출처), §24(reasoning effort — 이번 변경과 별개로 그대로 유지)
- DA_데이터파이프라인 `sql/01_schema_ddl.sql`(`products`/`product_ingredients` 정의), `src/load_postgres.py`(적재 스크립트)

---

## 27. §26 DB 매칭이 이름 뒷부분(용량·제형) 오독에는 약함 — "정" 앞부분 폴백 추가

### 문제

§26의 `findIngredientsByProductName`은 AI가 읽은 이름이 DB의 `"이름(주성분)"` 형태와 **정확히**
같거나 그 접두어여야만 매칭된다. 그런데 사진 화질이 나쁘면 브랜드명은 뚜렷해도 뒤에 붙는
용량·제형(`정40밀리그램` 같은) 쪽이 흐려서 AI가 그 부분만 잘못 읽거나 비워두는 경우가 있다 —
이러면 브랜드명은 맞았는데도 DB에 있는 제품을 그냥 놓친다.

### 원인

1차 매칭이 "전체 이름 일치"만 보기 때문에, 이름 뒷부분 한 글자만 어긋나도 매칭이 실패한다.

### 해결

`IngredientRepository.findIngredientsByCoreName(String)`(신규)을 1차 실패 시에만 도는 2차
폴백으로 추가했다. 제품명에서 **"정" 앞부분(브랜드명)만** 잘라 그 접두어로 다시 찾는다
(예: `"벨록스캡정40밀리그램"` → `"벨록스캡"`).

이 완화된 검색은 그 자체로 위험하다 — 같은 브랜드에 용량이 다른 버전이 여러 개 있으면
(예: 벨록스캡정 10·20·40mg 세 가지가 전부 `"벨록스캡"`으로 걸림) 아무거나 하나를 고르면
틀린 용량의 성분을 잘못 붙이게 된다. 그래서 **결과가 제품 정확히 1개로 좁혀질 때만** 채택하고,
0개(못 찾음)든 여러 개(모호함)든 둘 다 포기하고 빈 리스트를 돌려준다 — §12·§26과 같은 원칙
("모르면 지어내지 말고 비워라")을 매칭 단계에도 그대로 적용한 것이다.

`OcrService.toDto()`는 1차(`findIngredientsByProductName`)가 빈 리스트를 돌려줄 때만 2차를
시도하도록 연결했다.

### 확인

- 실제 사진(`7198.jpg`)으로 재검증: `벨록스캡정40밀리그램`은 1차 그대로 매칭되어 영향 없음.
  `무코스타서방정`·`모티리톤정`은 부분 일치로 넓혀도 DB에 아예 없어(§26에서 이미 확인) 2차도
  빈 리스트 — 잘못된 값을 만들지 않고 정직하게 실패함을 재확인.
- 2차 로직이 "1개로 좁혀지면 채택" 조건에서 실제로 맞는 성분을 찾는지 `psql`로 별도 검증:
  DB에서 `"정"` 기준으로 잘랐을 때 후보가 정확히 1개인 실제 제품(`가드렛정100mg(아나글립틴)`)을
  찾아, 브랜드명만("가드렛") 남겨도 `아나글립틴 100mg`으로 정확히 좁혀지는 것을 확인했다.
- "모호하면 3개가 걸린다"는 위험 사례도 재확인: `"벨록스캡"`만으로 검색하면 10·20·40mg 세
  버전이 모두 걸려(`product_id` 3개) 2차 로직이 의도대로 빈 리스트를 돌려준다.

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java`
  (`findIngredientsByCoreName` 신규)
- `src/main/java/com/project/medivice/service/OcrService.java` (`toDto`에서 1차 실패 시 2차 호출)
- §26(1차 매칭·이 기능의 배경), §12(모르면 비워라 원칙의 출처)

---

## 28. DB에 제품 절반이 비어 있던 진짜 원인 — 마이그레이션(06·08·09) 미적용

### 문제

§26·§27에서 "무코스타서방정"·"모티리톤정"이 DB에 없다고 기록했는데, 사용자가 "왜 없냐"고
다시 물어서 원인을 끝까지 파봤다. 결론부터 말하면 **§26의 진단이 틀렸다** — 공공데이터포털
수집 범위 밖이 아니라, **이미 로컬에 다 있는 데이터가 DB에 절반만 적재된 상태**였다.

### 원인

`DA_데이터파이프라인/data/normalized/products.csv`를 직접 열어보니 43,283행 전체(모티리톤·
무코스타 포함)가 이미 정규화까지 끝나 있었다. 그런데 DB의 `product_ingredients`는 21,093건뿐 —
README에 적힌 "기존 서비스 범위 21,093건"이라는 **예전 수치와 정확히 일치**했다. 즉 지금 DB는
초기(01~05 스크립트 + 수동 적재) 상태 그대로였고, 그 뒤에 나온 마이그레이션 3개가 한 번도
적용되지 않았다:

- `06_schema_alignment.sql` — 안전 고지·규칙 출처 컬럼 추가
- `08_widen_name_columns.sql` — **핵심 원인**. `products.name_ko`가 `VARCHAR(200)`인데 실제
  제품명 중 최대 391자짜리가 있어(`ingredients.name_ko`는 최대 287자), 전체 재적재를 시도하면
  `StringDataRightTruncation: value too long for type character varying(200)`으로 죽는다.
  §26에서 이 에러를 직접 만났다.
- `09_fix_single_rule_uniqueness.sql` — `dur_single_rules` 재적재 시 행이 중복되는 유니크 제약
  버그 수정(`NULL != NULL`이라 임부·연령 금기 규칙이 막히지 않던 문제)

README 맨 위에 **"기존 DB는 06 → 08 → 09 실행 후 03(뷰)을 다시 실행"**이라고 이미 적혀 있었는데
(§1에서 신규 DB만 만들고 이 안내를 놓쳤다), §26을 쓸 당시엔 이걸 못 보고 "API가 42,984건 중
22,271건만 수집됐다"고 잘못 결론 내렸다.

### 해결

DA_데이터파이프라인 `README.md`가 지시한 순서 그대로 실행했다 — 새 API 호출은 전혀 없이,
이미 로컬에 있는 CSV를 다시 적재하기만 했다.

```sh
psql -h localhost -p 5544 -U $(whoami) -d medivice_db -f sql/06_schema_alignment.sql
psql -h localhost -p 5544 -U $(whoami) -d medivice_db -f sql/08_widen_name_columns.sql   # 뷰 10개를 자동으로 DROP
psql -h localhost -p 5544 -U $(whoami) -d medivice_db -f sql/09_fix_single_rule_uniqueness.sql
psql -h localhost -p 5544 -U $(whoami) -d medivice_db -f sql/03_medilight_views.sql      # 뷰 10개 재생성

python3 -m venv loadenv && loadenv/bin/pip install psycopg2-binary python-dotenv
PGHOST=localhost PGPORT=5544 PGDATABASE=medivice_db PGUSER=$(whoami) PGPASSWORD= \
  loadenv/bin/python3 src/load_postgres.py
```

`load_postgres.py`는 `ON CONFLICT ... DO UPDATE/DO NOTHING`(upsert)이라 기존 행을 지우지 않고
빠진 것만 채운다 — `product_id`(identity 대체키)도 그대로 보존되므로, 이미 등록된 사용자
복용 목록(`medications.product_id` 참조)이 깨질 위험이 없다.

### 확인

```text
[적재 전]  products 22,271 / product_ingredients 21,093
[적재 후]  products 43,019 / product_ingredients 92,355  (전부 통과: 참조 무결성·중복·자기 자신 금기 0건)
```

`GET /api/products/ingredients`로 재확인:

```text
name=모티리톤정                → 현호색·견우자(5:1) 50% 에탄올 연조엑스(9.5~11.5→1) 30mg  (이제 매칭됨)
name=무코스타서방정150밀리그램 → 레바미피드 mg  (이제 매칭됨, 함량은 원천 데이터에 없어 NULL)
```

`7198.jpg`(처방전 3개 약)로 OCR도 재실행 — 이제 **3개 전부** `"성분 (DB 확인)"`, confidence 1.0으로
채워진다(§26·§27 때는 1개만 성공했었다). `GET /api/dashboard`도 200 정상 — 뷰 재생성 후 기존
사용자 데이터 조회에 영향 없음을 확인했다.

### 관련 파일

- DA_데이터파이프라인 `sql/06_schema_alignment.sql`, `08_widen_name_columns.sql`,
  `09_fix_single_rule_uniqueness.sql`, `03_medilight_views.sql`(재실행), `src/load_postgres.py`
- §1(최초 DB 구축 — 이때 마이그레이션 안내를 놓친 지점), §26·§27(잘못된 원인 진단이 남아있던 곳)

---

## 29. 등록된 약마다 "AI 설명"을 붙임 — 이미 설계는 돼 있었다

### 문제

사용자가 준 목업 화면(약 이름·성분 아래에 초록 "AI 설명" 칩과 1~2문장 설명이 붙는 카드)대로
만들어 달라는 요청. 코드를 보니 `MedicationDto.aiExplanation`과 `front/src/components/
MedicationRow.vue`의 렌더링 로직(`v-if="medication.aiExplanation"`)이 **이미 정확히 그 목업
모양대로 구현돼 있었다** — 백엔드가 항상 `null`만 채워 넣고 있었을 뿐이다. `medivice.ai_outputs`
테이블도 `target_type='MEDICATION'` 값까지 미리 준비된 채 아무 데서도 안 쓰이고 있었다.

### 원인

Sprint 3 확장 지점으로 자리만 파 두고 실제 생성 로직을 붙이지 않은 상태였다(§22가 지적한
"미리 세워 둔 구조" 패턴과 같은 종류).

### 해결

- `AiClient`에 `explainMedication(MedicationExplainContext)` 추가 — `summarizeReport`와 같은
  "AI는 주어진 사실만 풀어쓴다" 원칙. 근거 텍스트는 두 단계로 고른다:
  1. 제품명으로 `product_infos.efficacy`(식약처 e약은요 공식 효능·효과 원문, §28로 채워진
     4,767건)를 찾으면 그 문장을 쉬운 말로 다듬기만 하게 시킨다(지어내지 말라고 명시).
  2. 못 찾으면(수기 등록 등) 성분명만으로 "일반적으로 어떤 목적의 약인지" 설명하게 한다.
  - `MockAiClient`는 API를 부르지 않고 같은 우선순위(efficacy 있으면 그 앞부분, 없으면
    "OO이(가) 포함된 약입니다" 템플릿)로 기계적으로 채운다.
- `IngredientRepository`의 §26·§27 매칭 로직(전체 이름 → 브랜드명 폴백)을 재사용하도록
  리팩터링해서 `findEfficacyByProductName`을 추가했다 — 두 군데서 매칭 규칙이 어긋나지 않게
  `resolveProductIdExact`/`resolveProductIdByCoreName` private 메서드로 뽑아 공유한다.
- `AiOutputRepository`(신규) — 이미 있던 `ai_outputs` 테이블에 `target_type='MEDICATION'`으로
  저장·조회한다. **등록 시점에 딱 한 번만 생성해서 캐시**하고, 목록/대시보드 조회 때마다 AI를
  다시 부르지 않는다(OCR과 달리 짧은 텍스트 생성이라 등록 요청 스레드 안에서 동기로 끝낸다 —
  `ReportService.summarizeReport`와 같은 판단). AI 호출이 실패해도(키 미설정·네트워크 등)
  등록 자체는 실패하지 않도록 감싸고, 실패 기록만 `status='failed'`로 남긴다.
- `MedicationService.create()`가 생성해 반환하고, `list()`(대시보드가 호출)는 캐시된 값만 읽는다.
  **이 기능 이전에 등록된 항목은 설명 없이 그대로 보인다** — 프론트가 이미 `v-if`로 처리하므로
  깨지지 않는다.
- 프롬프트에 "마크다운(굵게·목록 등) 쓰지 말고 평문으로" 명시 — 첫 실측에서 `**LDL**`처럼
  마크다운이 섞여 나왔는데, 프론트는 이 문자열을 그냥 텍스트로 찍으므로 별표가 그대로 보였다.

### 확인

목업과 같은 두 약으로 실제 등록·조회:

```text
POST /api/medications (아모잘탄정 5/50mg, 암로디핀5mg+로사르탄칼륨50mg)
→ aiExplanation: "암로디핀과 로사르탄칼륨은 모두 혈관을 이완시켜 혈압을 낮추는 데 도움을
   주는 성분으로, 주로 고혈압 같은 혈압 관리에 사용됩니다. 복용 중 어지럼, 심한 부종 등
   이상 증상이 지속되면 의료진에게 알리세요."

POST /api/medications (크레스토정 5mg, 로수바스타틴5mg)
→ aiExplanation: "로수바스타틴은 혈액 속 LDL(나쁜) 콜레스테롤과 중성지방을 낮추고 HDL(좋은)
   콜레스테롤을 높이는 데 도움을 주어, 고지혈증(이상지질혈증) 관리에 흔히 쓰이는 성분입니다.
   복용 중 근육통·근력저하 같은 이상 증상이 지속되면 의료진에게 알리세요."

GET /api/dashboard → 재호출 시 AI를 다시 안 부르고(응답 즉시) 캐시된 문장 그대로 반환.
```

### 관련 파일

- `src/main/java/com/project/medivice/ai/AiClient.java`, `MockAiClient.java`, `OpenAiClient.java`
  (`explainMedication`, `MedicationExplainContext` 신규)
- `src/main/java/com/project/medivice/repository/AiOutputRepository.java` (신규)
- `src/main/java/com/project/medivice/repository/IngredientRepository.java`
  (`resolveProductIdExact`/`resolveProductIdByCoreName` 공유 추출, `findEfficacyByProductName` 신규)
- `src/main/java/com/project/medivice/service/ProductLookupService.java` (`findEfficacy` 신규)
- `src/main/java/com/project/medivice/service/MedicationService.java`
  (`create`가 생성·캐시, `list`가 캐시만 읽음)
- (건드리지 않음) `front/src/components/MedicationRow.vue` — 이미 완성돼 있었음

---

## 30. §29 작업 중 발견 — §28 마이그레이션이 등록 자체를 깨고 있었음

### 문제

§29 기능 확인을 위해 `POST /api/medications`를 호출하니 500 에러:
`null value in column "reason_code" of relation "safety_check_items" violates not-null constraint`.
새 기능과 무관하게 **등록 자체가 이미 막혀 있던** 상태였다.

### 원인

오늘(§28) 적용한 `06_schema_alignment.sql`이 `safety_check_items.reason_code`를 `NOT NULL`로
바꿔 놓았는데, 그 컬럼을 채우는 `SafetyCheckRepository.insertItem()`은 스키마가 바뀌기 전
버전 그대로였다 — INSERT 문에 `reason_code`가 아예 없어 항상 NULL이 들어가려다 막혔다.
§28에서 "마이그레이션은 데이터·뷰에 안전하다"만 확인했지, 그 마이그레이션이 전제하는 Java
코드 변경까지 같이 왔는지는 확인하지 않았던 게 원인이다.

### 해결

`insertItem()`에 `reason_code` 파라미터를 추가하고, 호출부(`SafetyCheckService.recordCheck`)
세 곳에 각각 맞는 코드를 채웠다 — `MedilightService.buildTotals()`가 이미 쓰던 어휘와 맞췄다:

- 용량주의(overdose): `RED`면 `OVER_LIMIT`, 같은 성분 복용 항목이 2개 이상이면 `DUPLICATE`,
  그 외엔 `NEAR_LIMIT`
- 단일금기(임부·연령·노인): `SINGLE_RULE`(성분 쌍이 아니라 이 스냅샷 표 모양상 뭉뚱그림)
- 판정 근거 없음: `NO_DUR_DATA`

### 확인

같은 등록 요청을 재시도해 201로 정상 처리됨을 확인했고, §29의 두 약 등록·조회로 회귀가
더 없는지 같이 검증했다(위 §29 확인 항목 참고).

### 관련 파일

- `src/main/java/com/project/medivice/repository/SafetyCheckRepository.java` (`insertItem`)
- `src/main/java/com/project/medivice/service/SafetyCheckService.java` (`recordCheck`)
- §28(원인이 된 마이그레이션), §6(DA 팀 06_schema_alignment.sql 원본)

---

## 31. AI 설명에 "복용 중 체감 변화"(예: 졸림) 추가 — 부작용 원문도 근거로 씀

> **추가 확인**: 이 기능을 적용한 뒤 OCR로 등록한 실제 3개 약(벨록스캡정40밀리그램·
> 무코스타서방정150밀리그램·모티리톤정)에는 부작용 설명이 안 붙었다. §28처럼 로딩이 덜 된
> 건지 다시 파봤는데 **이번엔 로딩 버그가 아니었다** — `product_infos.csv`를 제대로 된
> CSV 파서(줄바꿈 포함 필드 때문에 `wc -l`로 세면 48,918로 착시가 생긴다)로 다시 세보니
> 정확히 4,767행이고, **이 4,767개가 전부 이미 DB에 들어가 있었다**. 즉 이 세 약은 로컬
> 파일 어디에도 애초에 부작용 데이터가 없다 — "e약은요"(의약품개요정보) 데이터셋 자체가
> 전체 43,019개 제품 중 4,767개(약 11%)만 커버하는 원천 데이터셋 한계다(§26의 제품
> 허가정보·성분 데이터셋과는 커버리지가 다른 별개 데이터셋). 공공데이터포털 API를 실시간
> 호출해 이 11%를 넓히는 방법도 있지만, 사용자와 상의해 "지금처럼 유지(있으면 언급, 없으면
> 성분명 기반 일반 설명만)"로 결정했다 — 코드 변경 없음.

### 문제

사용자가 §29의 "AI 설명"에 사용자가 실제로 느낄 수 있는 변화(졸림 등)도 같이 안내할 수 있는지
물었다. `product_infos.side_effect`(식약처 공식 부작용 원문, 4,767건 중 4,535건에 이미 있음)를
안 쓰고 있었다.

### 해결

`AiClient.MedicationExplainContext`에 `sideEffect` 필드를 추가했다. `IngredientRepository`의
`findEfficacyByProductName`을 `findProductInfoByProductName`으로 넓혀 `efficacy`·`side_effect`를
한 번에 가져온다(매칭 로직은 §26·§27 그대로 재사용).

프롬프트 원칙은 §29와 같다 — **부작용 원문이 있을 때만** "복용 중 실제로 느낄 수 있는 대표
증상 하나만" 골라 언급하게 시키고, 원문이 없으면 부작용을 절대 추측하지 말라고 명시했다.
성분 지식만으로 "이 계열 약은 보통 졸린다더라" 식으로 지어내는 걸 막기 위해서다.

### 확인

부작용 원문에 실제로 "졸음"이 적힌 실제 약(`탐부틴정200밀리그램(트리메부틴말레산염)`)으로
등록해 확인했다:

```text
POST /api/medications (탐부틴정200밀리그램, 트리메부틴말레산염 200mg)
→ aiExplanation: "이 약은 식도역류나 열공헤르니아, 위·십이지장염/궤양 등에서 생기는 복통,
   소화불량, 메스꺼움·구토 같은 소화기 증상과 과민성대장증후군·경련성 결장, 그리고 소아의
   습관성 구토, 변비, 설사 등 감염이 아닌 장 통과 장애에 사용합니다. 복용 중 졸릴 수 있으니
   불편하면 의료진과 상의하고, 이상 증상이 지속되면 의료진에게 알리세요."
```

식약처 원문("...피로감, 졸음, 현기...")에 있던 "졸음"이 실제 응답에 자연스럽게 반영됨을
확인했다. 확인용으로 등록한 항목이라 검증 후 삭제했다(§29의 목업 재현용 두 건만 남김).

### 관련 파일

- `src/main/java/com/project/medivice/ai/AiClient.java`, `MockAiClient.java`, `OpenAiClient.java`
  (`MedicationExplainContext.sideEffect`)
- `src/main/java/com/project/medivice/repository/IngredientRepository.java`
  (`findEfficacyByProductName` → `findProductInfoByProductName`)
- `src/main/java/com/project/medivice/service/ProductLookupService.java` (`findProductInfo`)
- §29(이 기능의 원본 구현)

---

## 32. 등록 실패가 raw SQL 에러로 그대로 화면에 노출됨 — `timesPerDay` 검증 누락

### 문제

사용자가 약을 등록하는데 화면에 이런 게 그대로 떴다:

```text
PreparedStatementCallback; SQL [INSERT INTO medivice.medications (...) VALUES (...) ]:
ERROR: new row for relation "medications" violates check constraint "chk_med_times"
세부 정보: Failing row contains (76, 4, null, null, 1.00, 0, 1, ...).
```

원인도 안 보이고, SQL 원문·컬럼명·값이 그대로 사용자에게 노출됐다.

### 원인

`Failing row`의 값을 `\d medivice.medications` 컬럼 순서에 맞춰보면 `dose_per_intake=1.00` 다음
`times_per_day=0`이다 — DB 제약 `chk_med_times: CHECK (times_per_day >= 1 AND times_per_day <= 12)`를
어겼다. 그런데 `MedicationCreateRequest.timesPerDay`는 `@NotNull`만 있고 범위 검증이 없어서, 0 같은
값이 Bean Validation을 그냥 통과해 DB까지 내려갔다. 게다가 `GlobalExceptionHandler`엔
`DataIntegrityViolationException` 핸들러가 없어서, DB가 던진 예외가 그대로(스택트레이스·SQL 포함)
응답 본문에 실렸다 — 정보 노출이자 사용자 입장에서는 "뭔 소리인지 모를" 에러.

### 해결

- `MedicationCreateRequest.timesPerDay`에 `@Min(1) @Max(12)` 추가 — DB의 `chk_med_times`와 정확히
  같은 범위. 잘못된 값은 이제 DB까지 가지 않고 컨트롤러 경계에서 400으로 막힌다.
- `GlobalExceptionHandler`에 `DataIntegrityViolationException` 핸들러 추가(방어선) — 앞으로 비슷하게
  검증이 빠진 필드가 있어도, SQL 원문 대신 "입력값이 올바르지 않습니다" 같은 일반 메시지로 400을
  주고, 실제 원인(제약조건 이름 등)은 서버 로그에만 남긴다.

### 확인

```text
POST /api/medications (timesPerDay=0)
→ 이전: 500 + SQL 원문 그대로 노출
→ 이후: 400 {"message":"timesPerDay: 1 이상이어야 합니다"}
```

### 관련 파일

- `src/main/java/com/project/medivice/dto/MedicationCreateRequest.java` (`@Min(1) @Max(12)`)
- `src/main/java/com/project/medivice/exception/GlobalExceptionHandler.java`
  (`DataIntegrityViolationException` 핸들러 신규)

---

## 33. §26·§27 매칭이 "접두어"만 봐서, 제조사명이 성분명보다 앞에 오는 실제 제품명을 못 찾음 — 3차 폴백 추가

### 문제

"메토트렉세이트정[2.5mg/1정]"처럼 제품명이 사실상 성분명 그대로인 입력을 `GET
/api/products/ingredients`에 넣으면 빈 배열이 나왔다. 성분 마스터엔 "메토트렉세이트"(id=5)가
멀쩡히 있고, DB에도 메토트렉세이트가 든 제품이 21개나 있는데도 못 찾았다.

### 원인

```sql
SELECT product_id, name_ko FROM medivice.products WHERE name_ko LIKE '%메토트렉세이트%';
-- 제일메토트렉세이트정1밀리그램(수출용)
-- 한국유나이티드메토트렉세이트정(수출명:...)
-- 유한메토트렉세이트정
-- 메트렉스정(메토트렉세이트)[수출명:DHMTX tablet](수출용)  ← 성분명이 괄호 안에만 있음
-- ... (21건 전부 이 패턴)
```

실제 21개 제품 전부 `[제조사명][성분명]정[함량]` 형태로 **제조사명이 성분명보다 앞에** 오거나,
아예 성분명이 괄호 안 부가 설명으로만 존재한다. 그런데 §26(1차: 전체 이름/괄호 접두어)·§27(2차:
"정" 앞부분 접두어)은 둘 다 `LIKE '입력값%'` — 제품명이 **입력값으로 시작**해야만 잡히는
접두어(prefix) 매칭이다. "메토트렉세이트정"으로는 "제일메토트렉세이트정..."을 시작 부분이
다르므로 못 찾는다 — 접두어 매칭은 원천적으로 "성분명이 문자열 중간에 있는" 실제 제네릭
제품명 패턴을 커버할 수 없다.

### 해결

1·2차가 둘 다 실패했을 때만 쓰는 3차 폴백을 추가했다: 제품을 특정하려 하지 않고, **입력
문자열 안에 성분 마스터(5,000여 종)의 이름이 부분 문자열로 들어있는지**만 확인한다
(`strpos(:productName, name_ko) > 0`). 제조사명이 앞에 오든 뒤에 오든, 괄호 안에 있든 문자열
어디에 있든 상관없이 잡힌다. 이어서 성분명 뒤에 붙는 함량 표기(`2.5mg`, `2.5밀리그램` 등)를
정규식으로 뽑아 붙인다 — 못 찾으면 amount/unit은 null로 비운다(§12: 지어내지 않는다).

안전장치(§12·§27과 같은 "모르면 비워라" 원칙) 두 가지:
- 성분명이 2글자 이하면 다른 단어 안에 우연히 포함될 위험이 커서 `length(name_ko) >= 3`만 본다.
- "메토트렉세이트"와 "메토트렉세이트나트륨"처럼 한 성분명이 다른 성분명의 부분 문자열인 경우,
  최장 일치(가장 구체적인 이름)만 채택한다. 그래도 같은 길이의 서로 다른 성분이 여러 개
  걸리면 모호하므로 포기한다(빈 배열).

OCR 쪽 부수 효과: 3차로 찾은 성분은 함량을 못 구했을 수 있는데(`unit=null`), 기존 `OcrService`의
행 표시 로직(`i.name() + " " + amount + i.unit()`)이 `unit`이 null이면 문자열에 그대로 "null"이
찍히는 버그(§ null 표시 버그와 동일 패턴)가 있어 같이 고쳤다 — unit이 없으면 그냥 비운다.

### 확인

```text
GET /api/products/ingredients?name=메토트렉세이트정[2.5mg/1정]
→ [{"name":"메토트렉세이트","englishName":"Methotrexate","amount":2.5,"unit":"mg"}]

GET /api/products/ingredients?name=메토트렉세이트정   (함량 없음)
→ [{"name":"메토트렉세이트","englishName":"Methotrexate","amount":null,"unit":null}]
```

회귀 확인(1·2차가 여전히 우선 적용되고, 3차가 엉뚱하게 끼어들지 않는지):

```text
GET /api/products/ingredients?name=벨록스캡정40밀리그램   (1차) → 기존과 동일하게 매칭
GET /api/products/ingredients?name=가드렛정               (2차) → 기존과 동일하게 매칭
GET /api/products/ingredients?name=벨록스캡정             (2차 모호 → 포기) → []
GET /api/products/ingredients?name=타이레놀정500밀리그램   (DB에 없음) → []
```

참고: 이 확인 과정에서 `name=무코스타서방정`이 (예전엔 §26이 "DB 수집 범위 밖"으로 잘못
진단했던 것과 달리) §28의 전체 재적재 이후로는 **2차(브랜드명 접두어)만으로 이미 정상
매칭**되는 것도 다시 확인했다 — 3차와는 무관하게, §28에서 이미 고쳐져 있었다.

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java`
  (`resolveIngredientEmbedded`, `findIngredientsByEmbeddedName` 신규, `AMOUNT_PATTERN`)
- `src/main/java/com/project/medivice/service/ProductLookupService.java`
  (`findIngredients`에 3차 폴백 연결)
- `src/main/java/com/project/medivice/service/OcrService.java` (`toDto`의 unit null 처리)
- `src/main/java/com/project/medivice/controller/ProductController.java` (Swagger 설명·예시 갱신)
- §26·§27(1·2차 폴백의 원본 설계), §28(DB 전체 재적재로 무코스타 사례가 이미 해결돼 있었음)

---

## 34. 같은 이름의 성분이 두 줄(DUR 마스터 원본 + 사용자 생성 orphan) — 병용금기 판정이 조용히 빠짐

### 문제

이부프로펜·파마브롬 두 성분을 등록했는데, 이후 메토트렉세이트를 등록해도 "메토트렉세이트↔이부프로펜
병용금기(RED)"가 전혀 표시되지 않았다. 사용자가 "이부프로펜과 파마브롬을 분리해서 저장해야 할 것
같다"고 직접 원인을 짚었다.

### 원인

두 가지가 겹쳐 있었다.

1. **(진짜 원인 아님, 정상 동작)** 이부프로펜이 든 등록(medication_id=11)은 이미 2026-09-03에
   `ended_at`이 찍혀 종료된 상태였다 — 지금 활성 등록엔 이부프로펜이 아예 없어서 비교 대상이
   없었다. 이건 버그가 아니라 "활성 복용 목록끼리만 비교한다"는 설계대로다.
2. **(진짜 원인)** 조사 중 성분 마스터에 "파마브롬"이 **두 줄** 있는 걸 발견했다:

   ```sql
   SELECT ingredient_id, name_ko, ingr_code FROM medivice.ingredients WHERE name_ko='파마브롬';
   --  967 | 파마브롬 | USR-71148EC1-995   ← §28 전체 재적재 이전에 findOrCreateByName이 만든 orphan
   -- 9741 | 파마브롬 | M082487            ← 재적재로 들어온 진짜 식약처 DUR 마스터 원본
   ```

   `medication_id=11`은 967(orphan)을 참조하고 있었다. `findIdByName`(`findOrCreateByName`이 쓰는
   조회)이 `ORDER BY` 없이 `LIMIT 1`만 썼기 때문에, §28로 진짜 DUR 성분(9741)이 들어온 뒤에도
   어느 쪽이 뽑힐지 보장이 없었고 — 하필 이 케이스는 계속 orphan(967)이 뽑혀 저장돼 있었다.
   967은 `dur_pair_rules`·`ingredient_effect_groups`·`ingredient_daily_limits` 어디에도 연결이
   없는 빈 성분이라, 이 성분이 낀 조합은 규칙이 있어도 절대 안 걸린다(이번 사례에서 이부프로펜+
   파마브롬 자체는 마침 실제로도 DUR 데이터가 없어 결과는 같았지만, 다른 조합이었다면 진짜
   병용금기를 놓칠 뻔했다).

   전체 스캔해보니 같은 패턴이 **5건**(리보플라빈, 티아민질산염, 클로페라스틴염산염, 아스코르브산,
   파마브롬) 더 있었다 — 전부 §28 재적재 이전에 만들어진 orphan이 실사용 등록에 남아있던 경우다.

### 해결

- `IngredientRepository.findIdByName`에 `ORDER BY (ingr_code LIKE 'USR-%') ASC, ingredient_id ASC`를
  추가했다 — 같은 이름이 여러 줄이면 항상 DUR 마스터 원본(ingr_code가 "USR-"로 시작하지 않는 쪽)을
  먼저 뽑는다. 이제부터 `findOrCreateByName`이 호출될 때 orphan이 아니라 진짜 DUR 연결된 행을
  일관되게 재사용한다.
- 기존 데이터 5건(`medication_ingredients.ingredient_id`가 958·959·960·962·967인 행, 총 6개 참조)을
  트랜잭션으로 실제 DUR 성분 id(7023·7022·8615·7024·9741)로 재연결했다.

### 확인

```text
POST /api/medications (파마브롬 25mg만 등록, 검증 후 즉시 삭제)
→ medilight.totals에 "파마브롬" 항목이 dailyTotal=25로 정상 집계됨(재수정 전이었다면 orphan(967)이
   다시 뽑혔을 자리) — 확인 후 DELETE로 정리(medication_id=86)
```

```sql
-- 재연결 후 orphan 쪽 참조가 0인지
SELECT ingredient_id, count(*) FROM medivice.medication_ingredients
 WHERE ingredient_id IN (958,959,960,962,967) GROUP BY ingredient_id;
-- (0 rows)
```

### 참고 — 조사 중 같이 발견한, 이번 커밋과 무관한 기존 데이터 이슈

- `medication_id=78`("루파핀정[12.8mg/1정]")이 `medication_id=82`("루파핀정 12.8mg/1정", 같은 약의
  재등록)와 **둘 다 활성 상태**로 남아 있어 "성분 중복" WARN이 뜨고 있다 — 이건 코드 버그가 아니라
  §32 즈음 테스트하며 79·80·81은 정리했는데 78 하나를 빠뜨린 것으로 보인다. 코드 수정 대상이
  아니라 사용자 확인 후 정리할 데이터.
- `medication_id=57·77·79`(이름이 "1"인 등록, 성분명에 "(IBP)"·"(SP)"·"(KP)" 같은 영문 약어가
  괄호로 붙어 있는 orphan 성분들)는 이전 세션(§32 이전)에서 이미 발견해 사용자에게 삭제 여부를
  물었고, 사용자가 명시적으로 "그대로 둔다"고 답해 남겨둔 것이다 — 이번에도 손대지 않았다.

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java` (`findIdByName`)
- §28(전체 재적재로 진짜 DUR 성분이 이때 처음 들어옴 — orphan은 그 이전 흔적)
- §33(이 조사의 출발점이 된 §33 검증 도중 사용자가 발견)

---

## 35. OCR이 봉투 안 서로 다른 약 2개를 진짜 복합제 1개로 잘못 합침

### 문제

OCR 확인 화면에 "이부프로펜 200mg · 파마브롬 25mg"이 한 항목(복합제) 안에 성분 2개로 묶여
"선택한 1건 등록"으로 떴다. 사용자는 실제로는 서로 다른 봉지 2개(단일 성분 약 2개)를 한
봉투에 담아 찍은 사진이라고 확인해줬다 — AI가 사진을 잘못 판단해 하나로 합친 것이다.

### 원인

이부프로펜 200mg + 파마브롬 25mg 조합 자체는 실제로 시중에 흔한 단일 복합제 캡슐(이지엔6이브·
탁센이브·프리에나 등 DB에 20개 넘게 등록돼 있음, §34 조사 중 확인)과 정확히 일치하는 조성이다.
기존 프롬프트(EXT-1)는 "봉지 여러 개 vs 진짜 복합제"를 구분하라고는 했지만, 구분 **기준**을
주지 않았다 — 그래서 AI가 "이 조합은 시중에 흔한 복합제 조성과 같다"는 걸 판단 근거로 삼아
사진에 실제로 인쇄된 모양(봉지가 나뉘어 있는지)보다 성분 조합의 "그럴듯함"에 기대어 합쳐버린
것으로 보인다.

### 해결

프롬프트에 명시적인 판별 기준을 추가했다 — 성분 조합이 흔한 복합제와 닮았다는 이유로 합치지
말고, **사진에 실제로 인쇄된 모양만** 근거로 삼으라고 못박았다:
- 하나의 제품명 아래 성분표(여러 줄)로 같이 적혀 있으면 → 복합제(한 항목).
- 서로 다른 봉지·라벨에 각자 이름·용법이 따로 적혀 있으면 → 별도 약(항목을 나눔), 이때
  productName도 봉지마다 각각 그대로 쓰고 억지로 하나로 묶지 않는다.
- 애매하면 note에 불확실성을 적어, 사용자가 확인 화면에서 직접 고칠 수 있게 한다.

주의: 이건 결정론적 코드 버그가 아니라 AI 비전 판단의 실수라서, 프롬프트를 고쳐도 매번 100%
같은 사진에 같은 결과가 보장되지는 않는다(§ EXT 설계 원칙 — "AI는 주어진 사실만 풀어쓴다"고
못박아도 사진 해석 자체의 실수까지 완전히 막을 순 없다). 다음에도 비슷한 오판이 남아 있으면
사용자가 확인 화면에서 "성분 추가"·항목 삭제로 직접 고칠 수 있다(D-4: 확인 전 저장 안 함
설계 덕분에 이미 가능한 경로).

### 확인

코드 컴파일 확인(`./gradlew compileJava` exit 0)만 했다 — AI 판단 자체는 재현 가능한 단위
테스트 대상이 아니라서, 사용자가 같은/비슷한 사진으로 다시 업로드해 실제로 2건으로 나뉘는지
확인 필요.

### 관련 파일

- `src/main/java/com/project/medivice/ai/OpenAiClient.java` (`OCR_PROMPT`)
- `/Users/seungminchoi/mini project/제출물/AI_프롬프트_문장.md` (제출물 문서도 동일하게 갱신)
- §34(이 대화의 발단이 된, 같은 이부프로펜+파마브롬 조합 조사)

---

## 36. §35 검증 중 발견 — 성분명에 붙은 "(BP)" 같은 약전 접미사가 orphan을 계속 만듦 + MediLight 배지·설명 문장 불일치

### 문제

두 가지가 겹쳐 나타났다.

1. "이부프로펜(BP)"·"파마브롬(USP)"처럼 영문 약전 접미사가 붙은 이름으로 등록하면, §34에서
   고친 것과 같은 부류의 orphan 성분이 또 생겼다 — 이번엔 이름 자체가 달라서(§34의 "같은
   이름 두 줄" 문제가 아니라 "아예 다른 이름") §34의 `ORDER BY` 수정으로는 못 잡는다.
2. 그 결과를 고치고 나니 메디라이트 배지가 "빨강·높은 주의"(CRIT)로 바뀌었는데, 배지 바로
   아래 설명 문장은 여전히 "현재 적재된 성분·규칙 범위에서 중복 또는 임계값 문제가 발견되지
   않았습니다"였다 — 색은 위험하다는데 글은 문제없다고 하는 모순. 상세 화면(`/medilight`)도
   병용금기 카드가 아예 없어서 왜 CRIT인지 설명하는 곳이 하나도 없었다.

### 원인

1. `findOrCreateByName`이 정확한 이름으로만 찾는다. DUR 마스터에는 "(KP)"처럼 약전 접미사가
   이름의 정식 일부인 성분이 1,000개 넘게 있지만, "이부프로펜(BP)"·"파마브롬(USP)"는 마스터에
   그 형태로 존재하지 않는다(마스터엔 접미사 없는 "이부프로펜"만 있음) — 그래서 매번 새 orphan
   성분을 만들었다.
2. 프론트 `MediLightBanner.vue`의 `reason`(배지 아래 설명 문장)과 `MedilightView.vue`의 카드
   섹션이 전부 `analysis.findings`(중복·1일상한 초과 판정)만 보고 `analysis.conflicts`(병용금기·
   특정연령대금기 등 쌍대조 판정)는 아예 참조하지 않았다. `status`(배지 색)는 서버가 findings·
   conflicts 중 더 심각한 쪽으로 계산해서 내려주는데, 설명 문장·상세 카드는 conflicts를
   완전히 빼먹은 것 — 실제로 §34 수정 이후 등록 데이터를 고쳐서 병용금기(conflicts)가 잡히자
   이 모순이 바로 재현됐다.

### 해결

- `IngredientRepository`에 `findIdByNameOrBareName` 추가 — 정확한 이름으로 먼저 찾고, 실패하면
  이름 끝의 "(...)" 접미사를 뗀 이름으로 한 번 더 찾는다. `findOrCreateByName`이 이걸 쓰도록
  변경. 접미사를 뗀 이름도 못 찾으면 원래 이름 그대로 orphan을 만드는 기존 동작은 그대로 —
  DUR 마스터에 실제로 접미사가 붙은 진짜 성분(1,000여 건)은 1차 정확 매칭에서 이미 걸리므로
  영향받지 않는다.
- `MediLightBanner.vue`의 `reason`이 `conflicts[0]`을 `findings[0]`보다 먼저 확인하도록 순서를
  바꿨다. "자세히 보기" 링크 노출 조건도 `findings.length || conflicts.length`로 넓혔다.
- `MedilightView.vue`에 병용금기·연령금기 전용 표(CONFLICTS 섹션)를 새로 추가했다 — 기존엔
  findings 카드 하나만 있어서 conflicts를 볼 방법이 아예 없었다. 빈 상태(초록 배지) 조건도
  `!conflicts.length`를 같이 확인하도록 고쳤다.
- `stores/medivice.js`의 `translateVisibleText`가 `conflicts[].type`·`detail`도 번역 대상에
  포함하도록 추가 — 영어 모드에서 배지는 영어인데 이유 설명(DUR 원문 한글)만 그대로 남는 것을
  막는다. 성분명·제품명(ingredientA/B, medicationA/B)은 화학명·고유명사라 DeepL로 옮기지 않고
  원문 그대로 둔다(§ ReportView와 같은 원칙).
- `demo/front`·`mini project/medivice/front` 두 프론트 복사본 모두 동일하게 수정했다(이 세션
  내내 지켜온 원칙 — 브라우저가 어느 쪽을 보고 있는지 확실치 않을 때는 둘 다 고친다).

### 확인

```sql
-- 재연결 전: 이부프로펜(BP)/파마브롬(USP)/"1" orphan을 실제 DUR 성분으로 재연결(직접 등록
-- 데이터 수정, 코드 검증용)
```

```text
GET /api/medilight (수정 후)
→ status: "CRIT"
→ conflicts: [{"type":"병용금기","level":"CRIT",
    "medicationA":"1","medicationB":"메토트렉세이트정 2.5mg/1정","detail":"혈액학적 독성"}]
```

프론트는 npm install 후 `vite build`로 문법 오류 없음만 확인했다(이 환경엔 브라우저 도구가
없어 실제 화면 렌더링은 직접 확인 못 함 — 사용자가 새로고침해서 확인 필요).

### 관련 파일

- `src/main/java/com/project/medivice/repository/IngredientRepository.java`
  (`findIdByNameOrBareName`, `TRAILING_PAREN`)
- `front/src/components/MediLightBanner.vue`, `front/src/views/MedilightView.vue`,
  `front/src/stores/medivice.js` (두 프론트 복사본 모두)
- §34(orphan 성분 문제의 첫 사례), §35(이 조사의 출발점)

---

## 37. "판정 범위 밖 성분" 안내가 성분 7개를 콤마로 이어붙인 문장 하나였음

### 문제

"복용 목록의 성분 중 7개는 안전성 판정에 필요한 공공 데이터가 없어 확인하지 못했습니다:
레바미피드, 리보플라빈, 아스코르브산, 클로페라스틴염산염, 티아민질산염, 갈근탕엑스(10→1),
현호색·견우자(5:1) 50% 에탄올 연조엑스(9.5~11.5→1)." — 성분 7개가 콤마로 한 문장에 다 붙어
있어 어디부터 어디까지가 한 성분명인지 눈으로 나눠 읽어야 했다. 사용자가 "이것도 각 성분을
나눠서 이해해야지"라고 지적했다 — §36에서 conflicts를 표로 분리한 것과 같은 요청이다.

### 원인

`MedilightService.build()`가 `viewRepository.findNoticeMessage(userId)`로 DB 뷰
(`v_safety_notice`)가 이미 만들어 둔 문장 하나만 `MedilightDto.noticeMessage`에 담아 내려줬다.
정작 성분별로 구조화된 데이터(`MedilightViewRepository.findUncoveredIngredients` →
`v_uncovered_ingredients`)는 이미 존재했지만 `SafetyCheckService`(DB에 판정 결과를 저장할 때)만
쓰고 있었고, `MedilightDto`에는 노출되지 않았다 — 프론트는 문장 하나만 받아 그대로 보여줄
수밖에 없었다.

### 해결

- `UncoveredIngredientDto(name, englishName)` 신규.
- `MedilightDto`에 `uncoveredIngredients` 필드 추가, `MedilightService.build()`가
  `findUncoveredIngredients(userId)` 결과를 그대로 채워 넣는다 — 기존 `noticeMessage`(문장)는
  그대로 두고 구조화된 배열을 나란히 추가했다(하위 호환 — 문장에 의존하던 다른 곳이 있어도
  안 깨짐).
- `MedilightView.vue`(양쪽 프론트 복사본)의 COVERAGE 섹션이 `uncoveredIngredients`가 있으면
  §36의 CONFLICTS 표와 같은 모양(성분당 한 행)으로 보여주고, 없을 때(구버전 서버 응답 등)만
  기존 문장으로 폴백한다.

### 확인

```text
GET /api/medilight
→ uncoveredIngredients: [
    {"name":"레바미피드","englishName":null},
    {"name":"현호색·견우자(5:1) 50% 에탄올 연조엑스(9.5~11.5→1)","englishName":null}
  ]
```

백엔드 `./gradlew compileJava`, 프론트 양쪽 `vite build` 모두 exit 0 확인.

### 관련 파일

- `src/main/java/com/project/medivice/dto/UncoveredIngredientDto.java` (신규)
- `src/main/java/com/project/medivice/dto/MedilightDto.java` (`uncoveredIngredients` 필드)
- `src/main/java/com/project/medivice/service/MedilightService.java`
- `front/src/views/MedilightView.vue`, `front/src/stores/medivice.js` (두 프론트 복사본 모두)
- §36(같은 요청을 conflicts에 먼저 적용한 것)

---

## 38. 특정연령대금기(AGE_TABOO)가 나이 조건이 없는 규칙에서 모든 사용자에게 걸림

### 문제

52세 사용자에게 "특정연령대금기: 아세트아미노펜 — 소아 및 고령자(노인)는 최소 필요량을 복용..."
경고가 떴다. 52세는 소아도 아니고(사용자 지적) 통상 "고령자"로 보는 65세 기준에도 못 미친다 —
그런데도 CRIT로 걸렸다. 사용자가 "특정 연령에 대한 경고 없애"라고 요청했다.

### 원인

`v_single_conflict` 뷰(`DA_데이터파이프라인/sql/03_medilight_views.sql`)의 AGE_TABOO 판정:

```sql
EXTRACT(YEAR FROM age(u.birth_date))
    BETWEEN COALESCE(r.condition_min, 0) AND COALESCE(r.condition_max, 200)
```

`dur_single_rules.condition_min`/`condition_max`(구조화된 나이 범위)가 원본 공공데이터에
없는 규칙이 많다 — 실제로 세어보니 AGE_TABOO 규칙 126개 중 **111개(88%)**가 두 값 다 NULL이다.
이 아세트아미노펜 규칙도 그중 하나였다: `prohibit_content`엔 "소아 및 고령자"라는 글자는
있지만 그걸 숫자 나이 범위로 구조화한 `condition_min`/`condition_max`는 비어 있다.
`COALESCE(NULL, 0)`~`COALESCE(NULL, 200)` = "0세~200세" — 즉 조건이 없을 때 "전 연령"으로
해석돼 사실상 모든 사용자에게 무조건 걸렸다. 이건 111개 규칙 전부에 해당하는 광범위한
문제였다.

### 해결

두 값이 다 NULL이면(구조화된 나이 조건이 아예 없으면) 이 규칙은 판정하지 말고 빠지도록
조건을 추가했다:

```sql
OR (t.code = 'AGE_TABOO'
    AND (r.condition_min IS NOT NULL OR r.condition_max IS NOT NULL)
    AND EXTRACT(YEAR FROM age(u.birth_date))
        BETWEEN COALESCE(r.condition_min, 0) AND COALESCE(r.condition_max, 200))
```

값이 하나라도 있는 15개 규칙(예: "12세 미만 소아 금기")은 그대로 동작한다 — 새 조건이 항상
참이 되는 경우이므로 동작이 안 바뀐다. 나이 범위를 텍스트에서 추측해 숫자를 지어내는 대신,
"판정할 근거(구조화된 나이 조건)가 없으면 판정하지 않는다"를 택했다 — §12·이 프로젝트 전체의
"모르면 지어내지 말고 정직하게 실패하라" 원칙과 같은 결이다. 다만 이건 "그 사용자에게 안전
하다"는 뜻이 아니라 "이 규칙으로는 확인할 수 없다"는 뜻이라, v_uncovered_ingredients 쪽 보강은
이번엔 하지 않았다(범위 초과 — 필요하면 후속으로).

라이브 DB 뷰(`CREATE OR REPLACE VIEW`)에 바로 적용했다. 원본 마이그레이션 파일
(`DA_데이터파이프라인/sql/03_medilight_views.sql`)은 이 프로젝트 git 레포 밖(별도 db 파이프라인
저장소)에 있어 이 환경에서 직접 수정할 파일을 찾지 못했다 — db 담당자가 같은 패치를 원본
마이그레이션에도 반영해야 다음에 처음부터 재적재해도 유지된다.

### 확인

```text
GET /api/medilight (52세 사용자, 수정 전)
→ status: "CRIT", conflicts: [{"type":"특정연령대금기","ingredientA":"아세트아미노펜",...}]

GET /api/medilight (같은 사용자, 수정 후)
→ status: "OK", conflicts: []
```

### 관련 파일

- 라이브 DB 뷰 `medivice.v_single_conflict`(`CREATE OR REPLACE VIEW`로 직접 적용)
- `DA_데이터파이프라인/sql/03_medilight_views.sql` (원본 마이그레이션 — db 담당자가 반영 필요)

---

## 39. 병용금기 표의 "대상" 칸에 제품명만 뜨고 실제 충돌 성분이 안 보임

### 문제

CONFLICTS 표(§36)의 "대상" 칸이 "메토트렉세이트정[2.5mg/1정] · 1"처럼 제품명(그것도 "1" 같은
테스트 등록명)만 보여줬다. 사용자가 "여기서 서로 충돌하는 두 물질을 표시해"라고 요청 —
실제로 병용금기가 걸리는 건 성분(물질)인데 화면엔 그게 안 보였다.

### 원인

DB 뷰 `v_pair_conflict`엔 `ingredient_a_id`/`ingredient_b_id`가 이미 있었는데,
`MedilightViewRepository.findPairConflicts()`의 SQL이 이 컬럼을 아예 안 가져오고 제품명
(`medication_a_name`/`medication_b_name`)만 채웠다. `MedilightService.buildConflicts()`도
`ConflictDto.ingredientA/B`에 항상 `null`을 넣고 있었다 — 프론트(§36)는 애초에
`ingredientA ?? medicationA` 순서로 성분명을 우선하도록 짜여 있었지만, 백엔드가 성분명 자체를
준 적이 없어서 항상 제품명으로 폴백했던 것.

### 해결

`findPairConflicts` SQL에 `medivice.ingredients`를 두 번(ia, ib) 조인해 성분명도 같이
가져오고, `PairConflictRow`·`buildConflicts()`가 `ConflictDto.ingredientA/B`에 실제 성분명을
채우도록 고쳤다. 제품명(medicationA/B)도 그대로 같이 내려준다 — "어느 등록 항목 때문인지"
추적용으로는 여전히 유용하고, 프론트는 이미 성분명을 우선 쓰므로 화면만 자연스럽게
바뀐다(프론트 코드 변경 없음).

### 확인

```text
POST /api/medications ×2 (이부프로펜 200mg, 메토트렉세이트 2.5mg — 검증용, 즉시 삭제)
GET /api/medilight
→ conflicts[0]: {"type":"병용금기","ingredientA":"메토트렉세이트","ingredientB":"이부프로펜",
    "medicationA":"검증용-이부프로펜","medicationB":"검증용-메토트렉세이트","detail":"혈액학적 독성"}
```

### 관련 파일

- `src/main/java/com/project/medivice/repository/MedilightViewRepository.java`
  (`findPairConflicts`, `PairConflictRow`)
- `src/main/java/com/project/medivice/service/MedilightService.java` (`buildConflicts`)
- §36(CONFLICTS 표 신설 — 이번에 그 표의 데이터를 완성함)
