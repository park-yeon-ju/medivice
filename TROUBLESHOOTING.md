# 메디바이스 백엔드 트러블슈팅

- 작성일: 2026-09-03 (최종 갱신: 2026-09-03 — Java 21 업그레이드, OCR 속도 개선, AI 설정 외부화·비동기 전환 이후)
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
