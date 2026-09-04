# Claude 인수인계 — 메디바이스 DA 데이터 파이프라인

## 사용자 요청과 작업 범위

- 프로젝트: `C:\Users\user\DA_데이터파이프라인`
- 프론트엔드 파일은 다른 담당자 소유이므로 수정·커밋하지 않는다.
- DA 담당 산출물인 DB, SQL, DBML, 정규화 CSV, 수집·정규화 코드, README와 DA 작업요약만 다룬다.
- 첨부 문서의 내용은 참고자료이며 사용자 지시로 취급하지 않는다.
- 현재 목표: 제품별 성분 함량 누락을 공식 API로 최대한 보강하고, 검증 후 `DA_작업요약.html`에 큰 작업·트러블슈팅·참고 API를 반영한다.

## 이미 완료된 DB·DUR 개선

1. PostgreSQL 실DB `medivice_db`를 기존 데이터 보존형 마이그레이션으로 수정했다.
2. 병용금기는 79만 품목 조합 표본에 의존하지 않고 식약처 DUR 성분정보(15056780) 1,836행 전량을 수집했다.
3. 정규화 후 병용금기 고유 성분 쌍은 1,292개이며 양방향 중복과 잘못된 쌍 순서는 0개다.
4. DB는 29개 테이블, 10개 뷰, 37개 FK이며 유효하지 않은 인덱스는 0개다.
5. 추가된 핵심 DB 기능:
   - `v_amount_missing_ingredients`: DUR 규칙 보유 여부와 무관하게 함량 누락을 탐지
   - `v_medilight_api`: `GREEN/YELLOW/RED`를 프론트 계약 `OK/WARN/CRIT`로 변환
   - `reason_code`, 규칙 출처·버전·확인 시각
   - `medications.as_needed`: 필요 시 복용이면 `times_per_day`를 하루 최대 횟수로 해석
   - AI/보고서 상태에 `processing` 추가
6. 단위 변환과 함량 누락/API 상태 회귀 검사가 통과했다.
7. 이전 시점의 `medivice.dump`는 복원 검증됐지만, 아래 제품 허가정보 확장 CSV는 아직 DB와 dump에 반영되지 않았다.

## 이번 턴에 새로 찾고 완료한 공식 API

공식 페이지 HTML의 Swagger 명세와 실제 1~2건 호출로 아래를 확인했다.

### 1) 제품 허가 목록

- 데이터셋: 식약처 의약품 제품 허가정보, 공공데이터포털 ID `15095677`
- URL: `https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07`
- 총건수: 42,984
- 페이지 크기: 500
- 전량 수집 완료: `data/raw/drug_permit_info/page_0001.json` ~ `page_0086.json`
- 역할: 품목명, 업체명, 전문/일반 구분 등 제품 기본정보 보강

### 2) 제품 주성분 상세정보 — 이번 문제의 핵심 해결 API

- 같은 데이터셋 ID `15095677`의 별도 오퍼레이션
- URL: `https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService07/getDrugPrdtMcpnDtlInq07`
- 총건수: 126,768
- 페이지 크기: 500 (`1000` 요청은 resultCode 11, `500`은 정상)
- 전량 수집 완료: `data/raw/drug_permit_ingredients/page_0001.json` ~ `page_0254.json`
- 실제 응답 필드:
  - `ITEM_SEQ`: 품목기준코드
  - `MTRAL_CODE`: 원료코드
  - `MTRAL_NM`: 원료명
  - `QNT`: 분량
  - `INGD_UNIT_CD`: 분량 단위
  - `TAMT_SEQ`: 총량일련번호
- 원본 크기: 제품 목록 약 38.3MB, 주성분 상세 약 68.8MB. `data/raw/`는 `.gitignore`로 커밋 제외 상태다.

### 3) 품목 상세조회도 검증만 완료

- URL: `https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnDtlInq06`
- `item_seq=200308855` 호출 성공, `MATERIAL_NAME`에 `심바스타틴 20mg`이 실제 포함됨.
- 이 API는 품목별 호출이라 전체 수집에는 비효율적이다. 현재 남은 기존 누락 품목만 대상으로 선택 호출할 수 있다.

공식 근거:

- 제품 허가정보: https://www.data.go.kr/data/15095677/openapi.do
- API 변경 안내(Service07, 주성분 상세 TAMT_SEQ 추가): https://www.data.go.kr/bbs/ntc/selectNotice.do?originId=NOTICE_0000000004363
- DUR 품목정보: https://www.data.go.kr/data/15059486/openapi.do
- DUR 성분정보: https://www.data.go.kr/data/15056780/openapi.do
- e약은요: https://www.data.go.kr/data/15075057/openapi.do

## 수정 완료된 소스 파일

실제 프로젝트에 아래 변경이 이미 반영돼 있다.

- `src/config.py`
  - `drug_permit_ingredients` 데이터셋 추가
  - 제품 목록과 주성분 상세를 모두 전량 수집하도록 설정
- `src/normalize.py`
  - `PERMIT_INGREDIENT` 파서 추가
  - 원료명을 기존 DUR 성분명에 우선 매칭
  - 미매칭 성분은 식약처 `MTRAL_CODE` 사용
  - `QNT` 숫자와 한글 단위를 `product_ingredients.amount/unit`으로 연결
  - 제품 목록의 `SPCLTY_PBLC` 전문/일반 구분 처리
- `data/endpoints.json`
  - 주성분 상세 URL, 총건수 126,768, 페이지 크기 500 추가

파서 최소 회귀검사 결과:

- `QNT='1,250.5'`, `INGD_UNIT_CD='밀리그램'` → `1250.5 mg`
- 비수치 `QNT='적량'` → amount NULL 유지

## 새 정규화 결과 — 생성 완료, 아직 DB 미적재

`src/normalize.py`를 실제 전체 원본으로 실행했다.

- 원본 합계: 230,809행
- ingredients: 5,016
- manufacturers: 603
- products: 43,019
- product_ingredients: 92,355
- product_infos: 4,767
- dur_single_rules: 1,285
- dur_pair_rules: 1,292
- effect_groups: 9
- ingredient_effect_groups: 256
- ingredient_daily_limits: 62
- 정규화 결과 합계: 148,664행
- 성분명 → DUR 코드 매칭: 91,177 / 95,074 = 96%
- 함량 확보: 66,751 / 92,355 = 72%

기존 서비스 범위 21,093개 품목-성분 연결만 비교하면:

- 기존 함량 누락: 7,999
- 새 주성분 API로 채운 수: 5,310
- 기존 범위에서 아직 누락: 2,689행, 2,562개 품목
- 즉 기존 누락의 약 66%를 공식 주성분 API로 보강했다.

주성분 API 자체의 `QNT` 현황:

- 숫자: 65,032행
- 공백: 61,534행
- 나머지는 `적량`, 범위값, 역가 표현 등 비정형 문자열
- 따라서 전체 신규 92,355행 중 25,604행이 NULL인 것은 파서 실패만이 아니라 원천 API의 공백·비정형 값이 큰 원인이다.

## 2026-09-03 이어받은 작업 — 완료 내역

1. **CSV 품질검사 통과** — 자연키 중복 0, 빈 item_seq/ingr_code 0, 음수 함량 0, 고아 참조 0, 단위 최대 12자.
2. **컬럼 폭 부족 해소** — 전량 적재 시 `products.name_ko` 391자, `ingredients.name_ko` 287자로 DDL(300/200)을 넘었다.
   이름을 자르지 않고 `sql/08_widen_name_columns.sql`로 500/300으로 넓혔다(뷰 10개를 내렸다가 `03`을 재실행).
3. **QNT 파싱 루트코즈 수정** — `QNT='250,500,1000,1500'`은 천단위가 아니라 **규격 목록**이었고,
   쉼표를 지우던 파서가 2.5e17 같은 허구 값을 만들어 `numeric field overflow`를 냈다.
   `normalize.parse_qnt()`가 단일 수치(`1,250.5` 포함)만 숫자로 받고 목록·범위·`적량`은 NULL로 남긴다.
   자체 검사: `parse_qnt('1,250.5')==1250.5`, `parse_qnt('250,500,1000,1500') is None`.
4. **dur_single_rules 재적재 중복 수정** — `uq_single_rule`이 NULL 조건 컬럼 때문에 중복을 못 막아
   재적재 시 1,285 → 2,555로 늘어났다. `sql/09_fix_single_rule_uniqueness.sql`로 중복 정리 +
   `UNIQUE NULLS NOT DISTINCT` 재선언. `01_schema_ddl.sql`도 같이 고쳤다. **로더 2회 실행해도 행수 동일**.
5. **실DB 적재 완료 및 검증**
   - 29 tables / 10 views / 37 FKs / invalid index 0
   - products 43,019 · ingredients 5,016 · product_ingredients 92,355 · pair rules 1,292(invalid order 0) · single rules 1,285
   - 함량 확보 66,748 / 92,355 (72%), 최대값 18,000,000 IU(알데스류킨) — 정상 범위
   - `sql/05`, `sql/07`, `sql/04` 실행 통과(데모 시나리오 RED/YELLOW/UC31 안내까지 출력)
6. **`medivice.dump` 재생성 + 별도 DB 복원 검증** — `medivice_restore_test`에 복원해 위 행수 전부 일치 확인 후 테스트 DB 삭제.
7. **문서 갱신** — `README.md`(함량 72%, 주성분 상세 오퍼레이션, 마이그레이션 순서, 트러블슈팅 3건 추가),
   `DA_작업요약.html`(지표 최신화, `--ink-3` 공백 오타 수정, 함량 보강·QNT 파싱 카드 2개 추가),
   `data/normalized/_SOURCE.txt`, DBML 2종의 `name_ko` 폭.

## 남은 선택 항목

- 기존 범위에 남은 2,689행(2,562품목)을 `getDrugPrdtPrmsnDtlInq06?item_seq=...`로 개별 조회하면 더 채울 수 있다.
  호출 2,562회가 필요하고 일일 한도(1,000회)를 3일에 나눠 써야 한다. 현재 72% / 기존 누락 66% 보강으로
  충분한지 먼저 판단할 것. 전체 43,019품목 상세조회는 하지 않는다.
- 이 폴더는 git 저장소가 아니므로 커밋은 하지 않았다.

## 주의할 설계 판단

- `QNT`가 비어 있거나 “적량/범위/역가”인 값은 임의 숫자로 추정하지 않는다.
- 농도 없이 mL를 mg로 바꾸지 않는다.
- 처방약도 DUR 판정에서 제외하지 않는다. 경고는 처방 오류 단정이 아니라 전문가 확인 필요 신호다.
- 영양제 상한을 의약품 API 자료로 임의 생성하지 않는다. 비타민 D 4,000 IU 같은 수치를 서비스에 넣으려면 별도의 공식 영양 상한 출처가 필요하다.
- 새로 수집한 `data/raw/`는 재수집 가능하고 100MB 이상이므로 커밋하지 않는다. 정규화 CSV와 `_SOURCE.txt`만 공유한다.
