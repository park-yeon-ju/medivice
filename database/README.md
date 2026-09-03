# 메디바이스 데이터 파이프라인 (DA)

식약처 공공 API에서 의약품·DUR 데이터를 받아 **정규화**하고 **PostgreSQL에 적재**한 뒤,
**ERD(DBML)** 까지 만드는 전체 흐름. 담당 : 박기준 (DA)

```
공공데이터포털 API  →  collect  →  data/raw/*.json     (원본 그대로 보존)
                       normalize →  data/normalized/*.csv (1NF→2NF→3NF 적용)
                       load      →  PostgreSQL medivice 스키마
                                 →  dbml/*.dbml (dbdiagram.io 로 ERD 시각화)
```

> **팀원이라면 여기부터 읽지 마세요.** `docs/팀_공유_가이드.md` 를 먼저 보세요.
> 수집·정규화는 DA 한 사람만 돌립니다. 팀원은 `sql/` 3개 실행 + `load_postgres.py` 만 하면 됩니다.
> 인증키도, API 호출도 필요 없습니다.

## 현재 어디까지 되어 있나

| 단계 | 상태 | 비고 |
|---|---|---|
| ① API 수집 코드 | ✅ 완성 | **실행은 미완** — 개발 샌드박스에서 data.go.kr 접속이 막혀 있어 표본으로 검증했다. 엔드포인트는 `check_api.py`가 자동 확정한다 |
| ② 정규화 | ✅ 완성 + 검증 | 1NF~3NF 분해, 양방향 중복 제거까지 실제로 돌려 확인 |
| ③ 테이블 (DDL) | ✅ 완성 + 검증 | PostgreSQL 16에서 27개 테이블 생성 성공 |
| ④ 적재 | ✅ 완성 + 검증 | 자연키→대체키 치환, 참조 무결성 3종 통과 |
| ⑤ ERD / DBML | ✅ 완성 + 검증 | `@dbml/cli` 파싱 통과, DDL과 컬럼 150개 전수 일치 |
| ⑥ 판정 검증 | ✅ 완성 | 설계 문서 시나리오 재현 (YELLOW + UC31 안내 문구) |

**남은 것은 ①의 실행 하나뿐이다.** 인증키를 받아 `python src/collect_dur_api.py` 를 돌리면
표본 대신 실제 데이터가 `data/raw/`에 쌓이고, ②~⑥은 코드 수정 없이 그대로 이어진다.

---

## 0. 사전 준비 — 공공데이터포털 인증키 발급 (약 5분)

1. <https://www.data.go.kr> 회원가입 · 로그인
2. 검색창에 **"의약품 DUR품목정보"** 검색 → 목록에서 **오픈API** 탭 선택
3. 원하는 API의 **[활용신청]** 클릭 → 활용목적에 "학습/개발" 기재 → 신청
   - 자동승인 API라 보통 **즉시 승인**된다 (일부는 1~2시간)
4. **마이페이지 > 데이터활용 > 오픈API > 인증키 발급현황** 에서 **일반 인증키(Decoding)** 복사
5. 같은 방식으로 아래 API도 활용신청한다.
   | 데이터셋 ID | 이름 | 왜 필요한가 |
   |---|---|---|
   | 15059486 | 의약품 DUR품목정보 | 판정 규칙 6종. **활용신청 1건으로 오퍼레이션 6개를 모두 쓴다** |
   | 15095677 | 의약품 제품 허가정보 | **성분·함량(`MATERIAL_NAME`). 없으면 합산이 안 되어 판정이 스킵된다** |
   | 15075057 | 의약품개요정보(e약은요) | 효능·주의사항 원문 (UC19 AI 설명 근거) |
6. **[활용신청 상세]** 페이지의 **참고문서(엔드포인트 명세)** 를 열어, `src/config.py`의 `url` 값이
   실제 경로와 같은지 대조한다. 포털이 개편되면 뒤의 버전 접미사(`…03`)가 바뀐다.

> **Decoding 키를 쓸 것.** Encoding 키(`%2B`, `%3D`가 섞인 것)를 넣으면 `requests`가 한 번 더
> 인코딩해서 인증에 실패한다. 키는 `.env`에만 두고 절대 커밋하지 않는다 (`.gitignore`에 포함됨).

---

## 1. 환경 준비 (Windows PowerShell 기준)

```powershell
cd database

py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

> `Activate.ps1` 실행이 막히면 한 번만:
> `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`

### 인증키를 넣는 곳

**`database/.env`** 파일 딱 하나다. 코드 어디에도 키를 적지 않는다.

```
DATA_GO_KR_SERVICE_KEY=564955ce...(64자)
PGPASSWORD=본인_비밀번호
```

`.env`는 이미 만들어 두었으니 **`PGPASSWORD` 한 줄만** 본인 PostgreSQL 비밀번호로 바꾸면 된다.
`.gitignore`에 들어 있어 GitHub에 올라가지 않는다.

> **Encoding 키 / Decoding 키 어느 쪽인가?**
> 이 키는 64자 16진수(`0-9a-f`)라 특수문자가 없어 **두 형태가 동일**하다. 그냥 쓰면 된다.
> (`+`, `/`, `=`, `%2B` 가 섞인 예전 형식 키라면 반드시 **Decoding** 쪽을 써야 한다.)

### 실행 전 진단 — 반드시 먼저

```powershell
python src\check_api.py
```

세 가지를 한 번에 한다.

1. **인증키 점검** — 공공데이터포털은 실패해도 HTTP 200을 주므로, 원인(키 미승인 / 할당량 초과 /
   방화벽 / 경로 오류)을 구분해 알려 준다.
2. **엔드포인트 자동 확정** — 포털이 개편될 때마다 서비스명·오퍼레이션의 버전 접미사(`…03`, `…06`)가
   바뀐다. `config.py`에 후보를 나열해 두고, 이 스크립트가 차례로 찔러 **살아 있는 경로를 찾아
   `data/endpoints.json`에 기록**한다. 수집 스크립트는 그 파일을 읽으므로 경로를 손으로 고칠 일이 없다.
3. **페이지 크기 실측** — 식약처 API는 서비스마다 `numOfRows` 상한이 100인 것과 1000인 것이 섞여 있다.
   이 값을 모르면 수집에 필요한 호출 수를 10배 잘못 계산한다. 실제로 요청해 재서 함께 기록한다.

출력 예:

```
데이터셋                   셋ID       상태     설명
--------------------------------------------------------------------------
DUR 병용금기               15059486   OK       총 61,234건
의약품 제품 허가정보         15095677   OK       총 54,102건  (후보 2번째)
```

`(후보 2번째)`는 첫 후보가 아닌 다른 경로가 살아 있었다는 뜻이다. 정상이며, 이미 기록됐다.
전부 `OK`가 나온 뒤에 수집을 돌린다.

## 2. DB 스키마 생성

### 권장 — psql 없이 파이썬으로

Windows PostgreSQL 설치본은 `bin` 폴더를 PATH에 넣지 않아 `psql` 명령이 안 잡히는 경우가 많다.
그래서 psql 없이 같은 일을 하는 스크립트를 두었다.

```powershell
python src\setup_db.py --all
```

한 번에 **DB 생성 → 01·02·03 실행 → CSV 적재 → 데모 시나리오 출력**까지 끝난다.
나눠 하려면 `python src\setup_db.py` (스키마까지) → `python src\load_postgres.py` (적재).

접속이 안 되면 원인별로 안내한다(비밀번호 오류 / 서비스 중지 / 미설치).

### psql 을 쓰고 싶다면

PostgreSQL 설치 경로의 `bin` 을 PATH에 추가한다.

```powershell
$pg = (Get-ChildItem "C:\Program Files\PostgreSQL" | Select -Last 1).FullName
$env:Path += ";$pg\bin"      # 현재 창에서만 유효
psql --version
```

그다음:

```sql
CREATE DATABASE medivice_db;
\c medivice_db
\i sql/01_schema_ddl.sql
\i sql/02_seed_code.sql
\i sql/03_medilight_views.sql
```

## 3. 파이프라인 실행

```powershell
# 표본 데이터를 지우고 시작한다 — 실제 데이터와 섞이면 안 된다
Remove-Item -Recurse -Force data\raw, data\normalized -ErrorAction SilentlyContinue

python src\check_api.py          # ⓪ 진단  → data/endpoints.json
python src\collect_dur_api.py    # ① 수집  → data/raw/ (계획을 먼저 보여 주고 확인을 받는다)
#   중간에 끊겼다면:  python src\collect_dur_api.py --resume
python src\normalize.py          # ② 정규화 → data/normalized/
python src\load_postgres.py      # ③ 적재  → PostgreSQL (+ 무결성 검증)
```

⓪를 건너뛰고 ①을 돌리면 "먼저 진단을 돌리세요"라며 멈춘다. 확정되지 않은 경로로
수백 번 호출해 할당량만 태우는 일을 막기 위해서다.

### 호출 한도 — 이 프로젝트에서 가장 현실적인 제약

DUR 병용금기 하나가 **797,186건**이다. 100건씩 받으면 7,972회 호출이 필요한데
개발계정 일일 한도는 보통 **1,000회**다. 그대로 돌리면 중간에 막힌다.

`collect_dur_api.py` 는 실행 전에 계획을 보여 주고 확인을 받는다.

```
데이터셋                            전체           수집      예상 호출
------------------------------------------------------------------
DUR 병용금기                      797186        50000         50
...
합계                                                         122

서버 실측 페이지 크기 1000건 기준입니다.
일일 한도(1000회) 안에 들어옵니다.
진행할까요? [y/N]
```

한도를 넘으면 **얼마로 낮추면 되는지까지 계산해 알려 준다.**
상한은 `config.py` 의 `MAX_ROWS` 에서 조정한다.

> **상한을 두어도 설계 검증에는 지장이 없다.**
> 797,186건은 **품목 쌍** 기준이고, 정규화하면 **성분 쌍 수천 건**으로 접힌다.
> 그 압축 자체가 이 프로젝트가 보여 주려는 것이므로, 부분 수집으로도 근거가 성립한다.
> 전량이 필요하면 `MAX_ROWS` 값을 `None` 으로 바꾸고 며칠에 나눠 받으면 된다.

②가 끝나면 **성분 매칭률**과 **함량 확보율**이 찍힌다. 이 두 숫자가 낮으면
(예: 매칭 40%) 성분명 표기 차이 때문이므로 `normalize.py`의 `normalize_ingr_name()`을
손봐야 한다. 표본에서는 각각 83% / 100%였다.

인증키를 아직 못 받았다면 ①을 아래로 대체해 파이프라인 전체를 먼저 돌려볼 수 있다.
실제 API 응답과 **같은 구조**의 표본 JSON을 만든다.

```bash
python src/make_sample_raw.py
```

## 4. 설계 검증 (발표·문서 증빙용)

```sql
\i sql/04_demo_scenario.sql
```

설계 문서의 메인 화면 예시(아세트아미노펜이 두 제품에 겹치는 상황)를 데이터로 넣고
성분 전개 → 합산 → 메디라이트 색까지 실제로 계산되는 것을 보여 준다.
이 실행 결과 캡처가 §3 "DB 연동 확인" 증빙이 된다.

## 5. ERD 만들기

DBML 파일이 **두 벌** 있다. 쓰임이 다르다.

| 파일 | 언제 쓰나 |
|---|---|
| `광주2_○조_메디바이스-DB.dbml` | **제출용.** 순수 스키마 27개 테이블. 색상 구분은 있고 범례 박스는 없다 |
| `광주2_○조_메디바이스-DB_범례포함.dbml` | **발표 이미지용.** 위 내용 + 범례 3박스 + 읽는 순서 노트 |

두 파일의 스키마 부분은 **완전히 동일**하다(범례만 뒤에 덧붙었다).

### 순서

1. <https://dbml.dbdiagram.io> 접속
2. **`_범례포함.dbml`** 내용을 왼쪽 편집기에 붙여넣는다
3. 오른쪽에 관계선과 색상이 그려지면 정상
4. 범례 3박스를 **캔버스 왼쪽 위 빈 자리로 끌어다 놓는다** (자동 배치는 아무 데나 둔다)
5. 선이 겹치는 테이블은 드래그로 떼어 놓는다 — `ingredients`를 가운데 두면 정리가 쉽다
6. **Export > PNG** 로 발표자료·설계문서용 이미지 저장
7. 제출은 이미지가 아니라 **`.dbml` 텍스트 파일**로 하되, **제출용 파일**(범례 없는 쪽)을 낸다

### 색상이 뜻하는 것

| 색 | 의미 | 채점 항목과의 연결 |
|---|---|---|
| 🔵 파랑 | Layer A 참조 데이터 (공공 API 3종) | 외부 데이터 활용 |
| 🟢 초록 | Layer B 서비스 데이터 (사용자) | 화면 ↔ DB 추적성 |
| 🟠 주황 | N:M 교차 테이블 4종 | **다대다 관계 해소** |
| 🟣 보라 | 3NF로 분리한 코드 테이블 | **정규화** |

색을 그냥 예쁘게 칠한 게 아니라, **채점자가 보는 항목마다 색을 하나씩 배정**했다.
"주황 박스가 다대다를 푼 자리, 보라 박스가 중복 문자열을 뺀 자리"라고 한 줄로 설명할 수 있다.

### 로컬 문법 검증

```bash
npm i -g @dbml/cli
dbml2sql --postgres "dbml/광주2_○조_메디바이스-DB.dbml"
```

오류 없이 SQL이 출력되면 dbdiagram.io에서도 그대로 열린다.

---

## 파일 구성

| 경로 | 내용 |
|---|---|
| `src/config.py` | 수집 대상 API 목록 · 엔드포인트 · DB 접속 설정 |
| `src/collect_dur_api.py` | API 페이지 순회 수집 (원본 JSON 무가공 저장) |
| `src/normalize.py` | 1NF→2NF→3NF 분해 → 테이블별 CSV |
| `src/load_postgres.py` | 자연키→대체키 치환 적재 + 참조 무결성 검증 |
| `src/setup_db.py` | **psql 없이** DB·스키마 생성 (+`--all` 로 적재·데모까지) |
| `src/check_api.py` | 인증키 점검 + 엔드포인트 자동 확정 → `data/endpoints.json` |
| `src/make_sample_raw.py` | 인증키 없이 검증하기 위한 표본 원본 생성 |
| `sql/03_medilight_views.sql` 내 `v_uncovered_ingredients` | 판정 근거가 없는 성분을 초록과 구분 |
| `sql/01_schema_ddl.sql` | 테이블 26개 DDL |
| `sql/02_seed_code.sql` | 코드 테이블 시드 + 판정 경로 인덱스 |
| `sql/03_medilight_views.sql` | UC15·UC16 판정 뷰 |
| `sql/04_demo_scenario.sql` | 설계 문서 시나리오 재현 + 결과 확인 |
| `dbml/광주2_○조_메디바이스-DB.dbml` | **제출용** ERD (순수 스키마) |
| `dbml/광주2_○조_메디바이스-DB_범례포함.dbml` | **발표 이미지용** ERD (범례 박스 포함) |
| `docs/정규화_설계방향.md` | 정규화 근거 · 키/제약조건 설계 근거 문서 |
| `docs/UseCase_수정안.md` | UC31 신규 및 UC16·UC17·UC27 변경분 (설계 문서에 반영할 것) |
| `docs/팀_공유_가이드.md` | 누가 무엇을 돌리는가 · GitHub 커밋 범위 |

## 자주 막히는 지점

| 증상 | 원인 | 해결 |
|---|---|---|
| JSON 대신 XML 에러가 옴 | Encoding 키를 넣었다 | Decoding 키로 교체 |
| `resultCode=99` / PATH | 엔드포인트 경로 오류 | `check_api.py`가 후보를 자동 탐색한다. 전 후보가 실패하면 활용신청 상세의 참고문서에서 실제 경로를 확인해 `config.py`의 `urls` 목록에 추가 |
| `SERVICE_KEY_IS_NOT_REGISTERED` | 승인 대기 중 | 마이페이지에서 승인 상태 확인 |
| `resultCode=22` | 일일 호출 한도 초과 | 내일 재시도. **`--resume` 을 붙이면 이미 받은 페이지를 건너뛴다** |
| `resultCode=10` / `11` | 페이지 크기가 서버 허용치를 넘음 | 자동으로 한 단계 낮춰 재시도한다. 계속 나면 `check_api.py` 를 다시 돌려 상한을 재측정 |
| `resultCode=20` / `30` | 활용신청 미승인 / 키 오류 | 마이페이지에서 승인 상태 확인 |
| `.venv` 만들기가 느림 | 폴더가 OneDrive 동기화 대상 | OneDrive 설정에서 `.venv` 폴더를 동기화 제외하거나, 가상환경을 `C:\dev\medivice-venv` 처럼 밖에 만든다 |
| 적재 시 FK 오류 | 실행 순서 | `01 → 02 → load` 순서를 지킨다 |
| `ModuleNotFoundError: psycopg2` | **가상환경이 활성화되지 않음** | 프롬프트 앞에 `(.venv)` 가 있는지 확인. 없으면 `.\.venv\Scripts\Activate.ps1` |
| `'psql' 용어가 ... 인식되지 않습니다` | PATH 미등록 | `python src\setup_db.py` 를 쓰거나 위의 PATH 추가 |
