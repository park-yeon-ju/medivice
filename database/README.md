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
> 수집·정규화는 DA 한 사람만 돌립니다. 신규 DB는 `sql/01~03` 실행 + `load_postgres.py`,
> 기존 DB는 `sql/06_schema_alignment.sql` → `sql/08_widen_name_columns.sql` →
> `sql/09_fix_single_rule_uniqueness.sql` 실행 후 `sql/03_medilight_views.sql`을 다시 실행합니다.
> 인증키도, API 호출도 필요 없습니다.

## 현재 어디까지 되어 있나

| 단계 | 상태 | 비고 |
|---|---|---|
| ① API 수집 | ✅ 완성 | 공공데이터포털 의약품·DUR 실데이터 수집 완료 |
| ② 정규화 | ✅ 완성 + 검증 | 1NF~3NF 분해, 양방향 중복 제거까지 실제로 실행 |
| ③ 테이블 (DDL) | ✅ 완성 + 검증 | PostgreSQL 16에서 29개 테이블 생성 성공 |
| ④ 적재 | ✅ 완성 + 검증 | 제품 43,019건 등 정규화 CSV와 DB 행 수 일치, 참조 무결성 통과 |
| ⑤ ERD / DBML | ✅ 완성 + 검증 | 실제 DB 기준 29개 테이블·37개 FK를 전체 DBML에 반영 |
| ⑥ 판정 검증 | ✅ 완성 | 병용금기 RED, 성분 중복 YELLOW, UC31 안내 경로 확인 |

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
   | 15056780 | 의약품 DUR성분정보 | 병용금기 성분 규칙 전량. 79만 품목 조합을 받지 않고 같은 핵심 규칙을 확보한다 |
   | 15095677 | 의약품 제품 허가정보 | 품목 목록(42,984건) + **주성분 상세(126,768건). 함량이 없으면 합산이 안 되어 판정이 스킵된다** |
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
DATA_GO_KR_SERVICE_KEY=<발급받은_Decoding_키>
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
DUR 병용금기(품목)          15059486   OK       총 797,186건
DUR 병용금기(성분)          15056780   OK       총 1,836건
의약품 제품 허가정보         15095677   OK       총 42,984건
의약품 제품 주성분 상세      15095677   OK       총 126,768건 (페이지 500)
```

> 제품 허가정보는 **데이터셋 하나에 오퍼레이션이 둘**이다. 목록(`getDrugPrdtPrmsnInq07`)에는
> 성분·함량이 없고, 주성분 상세(`getDrugPrdtMcpnDtlInq07`)에만 `QNT`·`INGD_UNIT_CD`가 있다.
> 주성분 상세는 `numOfRows=1000`이면 `resultCode 11`로 거부되므로 500으로 받는다.

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

> **주의:** `setup_db.py`는 기존 `medivice` 스키마를 다시 만든다. 이미 데이터가 있는 공유 DB에는
> 실행하지 말고, 새 DB를 만드는 경우에만 사용한다.

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
DUR 병용금기(품목)                 797186        20000         40
DUR 병용금기(성분)                   1836         1836          4
...
합계                                                         122

서버 실측 페이지 크기 1000건 기준입니다.
일일 한도(1000회) 안에 들어옵니다.
진행할까요? [y/N]
```

한도를 넘으면 **얼마로 낮추면 되는지까지 계산해 알려 준다.**
상한은 `config.py` 의 `MAX_ROWS` 에서 조정한다.

> 현재 설정은 품목 병용금기 797,186건 중 20,000건을 전 구간 계통표본으로 받고,
> 성분 병용금기 1,836건은 전량 받는다. 성분 전량은 정규화 후 1,292개 고유 성분 쌍으로 접힌다.
> 서비스 판정의 기준은 이 성분 규칙이며, 품목 표본은 제품 예시와 원본 구조 검증용이다.
> 임의의 “유명 약” 규칙을 추가하지 않는다. 심바스타틴×이트라코나졸처럼 실제 공식 규칙과
> 보유 제품이 모두 있는 조합을 데모에서 선택한다.

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

실제 품목으로 심바스타틴×이트라코나졸 병용금기(RED), 이트라코나졸 성분 중복(YELLOW),
최종 메디라이트 RED와 UC31 안내 경로까지 계산되는 것을 보여 준다.
이 실행 결과 캡처가 §3 "DB 연동 확인" 증빙이 된다.

질량 단위는 판정 뷰에서 `g`·`mg`·`mcg`를 `mg`로 통일하고 `IU`는 별도로 합산한다.
농도 정보가 없는 `mL` 등은 질량 상한과 비교하지 않는다. 변환 회귀 검증은
검증용 DB에서 `sql/05_unit_conversion_check.sql`을 실행한다(마지막에 자동 `ROLLBACK`).

함량이 없는 성분은 DUR 규칙이 있더라도 `v_amount_missing_ingredients`에서 별도로 잡아
`v_safety_notice`에 안내한다. 프론트는 `v_medilight_api`의 `OK/WARN/CRIT`를 사용하고,
DB 내부 판정값 `GREEN/YELLOW/RED`는 그대로 보존한다. 회귀 검사는
`sql/07_alignment_check.sql`을 실행한다(테스트 데이터는 자동 `ROLLBACK`).

현재 정규화 결과의 함량 확보율은 **66,748/92,355(72%)** 다. 제품 허가정보의 *목록* 조회에는
`MATERIAL_NAME`이 없어 함량을 채울 수 없었고, 같은 데이터셋의 **주성분 상세 오퍼레이션**
(`getDrugPrdtMcpnDtlInq07`, 126,768행)을 254회로 전량 수집해 `QNT`·`INGD_UNIT_CD`로 함량을 채웠다.
기존 서비스 범위 21,093건 기준으로 보면 함량 누락 7,999건 중 **5,310건(약 66%)을 보강**했고
2,689건이 남는다.

남은 NULL은 파서 실패가 아니라 원천 API의 값 자체가 비어 있거나(`QNT` 공백 61,534행)
`적량`·범위·역가 같은 비정형 문자열인 경우다. 특히 `QNT='250,500,1000,1500'` 처럼
**규격 목록**이 내려오는 행이 있어, 쉼표를 천단위 구분자로 보고 지우면 250억 같은 허구의 함량이
만들어진다. `normalize.parse_qnt()`는 단일 수치(`1,250.5` 포함)만 숫자로 받고 목록·범위는
NULL로 남긴다. 추정하지 않는 쪽이 안전 판정에서 옳다.
함량 누락 항목은 일일 상한 비교에서 제외하되 위 고정 안내를 반드시 표시한다.

“필요 시 복용”은 `medications.as_needed=true`로 저장하고, `times_per_day`에는 안전 계산에 쓸
**하루 최대 복용 횟수**를 넣는다. 처방약도 DUR 판정에서 제외하지 않는다. 경고는 처방이 잘못됐다는
판정이 아니라 의사·약사 확인이 필요하다는 뜻이다.

## 5. ERD 만들기

DBML 파일이 **두 벌** 있다. 쓰임이 다르다.

| 파일 | 언제 쓰나 |
|---|---|
| `광주2_○조_메디바이스-DB.dbml` | **전체 물리 스키마·제출용.** 실제 DB와 같은 29개 테이블·37개 FK |
| `광주2_○조_메디바이스-DB_서비스ERD.dbml` | **서비스 화면 설명용.** 주요 22개 테이블과 범례를 추린 다이어그램 |

전체 구조의 기준 파일은 `광주2_○조_메디바이스-DB.dbml`이다. 서비스 ERD는 화면 설명을 위해
일부 코드·보조 테이블을 생략했으므로 전체 스키마 대용으로 사용하지 않는다.

### 순서

1. <https://dbml.dbdiagram.io> 접속
2. 목적에 맞는 DBML 내용을 왼쪽 편집기에 붙여넣는다(제출 검증은 전체 물리 스키마 파일)
3. 오른쪽에 관계선과 색상이 그려지면 정상
4. 서비스 ERD를 쓸 때는 범례 박스를 **캔버스 왼쪽 위 빈 자리로 끌어다 놓는다**
5. 선이 겹치는 테이블은 드래그로 떼어 놓는다 — `ingredients`를 가운데 두면 정리가 쉽다
6. **Export > PNG** 로 발표자료·설계문서용 이미지 저장
7. 제출은 이미지와 함께 **전체 물리 스키마 `.dbml` 파일**을 낸다

### 색상이 뜻하는 것

| 색 | 의미 | 채점 항목과의 연결 |
|---|---|---|
| 🔵 파랑 | Layer A 참조 데이터 (공공 API 4종) | 외부 데이터 활용 |
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
| `sql/01_schema_ddl.sql` | 테이블 29개 DDL |
| `sql/02_seed_code.sql` | 코드 테이블 시드 + 판정 경로 인덱스 |
| `sql/03_medilight_views.sql` | UC15·UC16 판정 뷰 |
| `sql/04_demo_scenario.sql` | 설계 문서 시나리오 재현 + 결과 확인 |
| `sql/05_unit_conversion_check.sql` | g·mg 단위 변환 회귀 검증(검증용 DB 전용, 자동 롤백) |
| `sql/06_schema_alignment.sql` | 기존 DB 보존형 마이그레이션(출처·함량누락·PRN·비동기 상태) |
| `sql/07_alignment_check.sql` | 함량 누락 고지와 `OK/WARN/CRIT` 매핑 회귀 검사 |
| `dbml/광주2_○조_메디바이스-DB.dbml` | **제출용** ERD (순수 스키마) |
| `dbml/광주2_○조_메디바이스-DB_서비스ERD.dbml` | **화면 설명용** ERD (주요 22개 테이블 + 범례) |
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
| `value too long for type character varying(300)` | 제품 허가정보 전량의 품목명이 최대 391자 | `sql/08_widen_name_columns.sql` 실행 (뷰를 내렸다가 `03`을 다시 실행) |
| `numeric field overflow` | `QNT`가 `250,500,1000,1500` 같은 **규격 목록** | `normalize.parse_qnt()`가 단일 수치만 숫자로 받는다. 정규화를 다시 실행 |
| `dur_single_rules` 행이 재적재마다 2배 | `condition_min/max`가 NULL이라 UNIQUE가 안 걸림 | `sql/09_fix_single_rule_uniqueness.sql` 실행 (`NULLS NOT DISTINCT`) |
| `ModuleNotFoundError: psycopg2` | **가상환경이 활성화되지 않음** | 프롬프트 앞에 `(.venv)` 가 있는지 확인. 없으면 `.\.venv\Scripts\Activate.ps1` |
| `'psql' 용어가 ... 인식되지 않습니다` | PATH 미등록 | `python src\setup_db.py` 를 쓰거나 위의 PATH 추가 |
