/*
작성자 : 박기준
작성목적 : 메디바이스(복약 안전 관리 서비스) DB 스키마 설계.
          식약처 공공 API(DUR·의약품개요)에서 수집한 참조 데이터와
          사용자가 생성하는 서비스 데이터를 하나의 스키마로 통합하고,
          UC15(성분 충돌 판정) → UC16(메디라이트) 실행 경로를 지탱하도록 정규화(1NF~3NF)한다.
작성일 : 2026-09-02
활용 파일 : 01_schema_ddl.sql(본 파일), 02_seed_code.sql(코드 테이블 시드),
           03_medilight_views.sql(판정 뷰), 광주2_○조_메디바이스-DB.dbml(ERD),
           src/collect_dur_api.py → src/normalize.py → src/load_postgres.py(적재 파이프라인)

변경사항 내역 (날짜, 변경목적, 변경내용 순으로 기입)
2026-09-02 - 최초 작성 - 참조 데이터 8개 테이블(ingredients ~ ingredient_daily_limits)과
             서비스 데이터 15개 테이블(users ~ family_links) DDL 작성.
             복합제 성분(1NF), 제조사·성분·진료과·증상 코드 분리(2NF·3NF),
             병용금기 쌍 중복 제거(chk_pair_order), N:M 교차 테이블 4종 도입.

설계 원칙
  ① 이 스키마는 두 레이어로 나뉜다.
       Layer A 참조 데이터  : 식약처 공공 API에서 배치로 받아 적재. 서비스는 읽기만 한다.
       Layer B 서비스 데이터: 사용자가 만든다. 런타임에 CRUD가 일어난다.
     두 레이어는 오직 ingredients(성분) 하나로 만난다.
     medications → ingredients → dur_* 조인 경로가 곧 메디라이트 판정 경로다.
  ② PK는 전부 GENERATED ALWAYS AS IDENTITY 대체키를 쓴다.
     식약처 품목기준코드(item_seq)·성분코드(ingr_code) 같은 자연키는 UNIQUE로 따로 관리한다.
     공공데이터 코드 체계가 개편되어도 FK 구조가 흔들리지 않게 하기 위함이다.
  ③ 판정에 쓰이지 않는 개인정보는 컬럼 자체를 만들지 않는다(주민등록번호 없음).
*/

--------------------------------------------------------------------------------
-- 1. DATABASE & SCHEMA 생성 (PostgreSQL 17 기준)
--------------------------------------------------------------------------------
-- 참고: DATABASE 생성은 DBeaver 또는 psql 접속 직후 별도 실행할 수 있습니다.
-- CREATE DATABASE medivice_db;
-- \c medivice_db;

CREATE SCHEMA IF NOT EXISTS medivice;
SET search_path TO medivice, public;

-- FK 역순으로 정리 (자식 → 부모)
DROP TABLE IF EXISTS medivice.notice_templates       CASCADE;
DROP TABLE IF EXISTS medivice.ingredient_effect_groups CASCADE;
DROP TABLE IF EXISTS medivice.effect_groups          CASCADE;
DROP TABLE IF EXISTS medivice.family_links           CASCADE;
DROP TABLE IF EXISTS medivice.reports                CASCADE;
DROP TABLE IF EXISTS medivice.side_effect_snapshots  CASCADE;
DROP TABLE IF EXISTS medivice.side_effect_symptoms   CASCADE;
DROP TABLE IF EXISTS medivice.side_effect_logs       CASCADE;
DROP TABLE IF EXISTS medivice.symptoms               CASCADE;
DROP TABLE IF EXISTS medivice.ai_outputs             CASCADE;
DROP TABLE IF EXISTS medivice.safety_check_items     CASCADE;
DROP TABLE IF EXISTS medivice.safety_checks          CASCADE;
DROP TABLE IF EXISTS medivice.medication_ingredients CASCADE;
DROP TABLE IF EXISTS medivice.medications            CASCADE;
DROP TABLE IF EXISTS medivice.prescriptions          CASCADE;
DROP TABLE IF EXISTS medivice.departments            CASCADE;
DROP TABLE IF EXISTS medivice.user_conditions        CASCADE;
DROP TABLE IF EXISTS medivice.user_allergies         CASCADE;
DROP TABLE IF EXISTS medivice.user_profiles          CASCADE;
DROP TABLE IF EXISTS medivice.users                  CASCADE;
DROP TABLE IF EXISTS medivice.ingredient_daily_limits CASCADE;
DROP TABLE IF EXISTS medivice.dur_pair_rules         CASCADE;
DROP TABLE IF EXISTS medivice.dur_single_rules       CASCADE;
DROP TABLE IF EXISTS medivice.dur_types              CASCADE;
DROP TABLE IF EXISTS medivice.product_infos          CASCADE;
DROP TABLE IF EXISTS medivice.product_ingredients    CASCADE;
DROP TABLE IF EXISTS medivice.products               CASCADE;
DROP TABLE IF EXISTS medivice.manufacturers          CASCADE;
DROP TABLE IF EXISTS medivice.ingredients            CASCADE;


--------------------------------------------------------------------------------
-- 2. DDL : Layer A — 참조 데이터 (식약처 공공 API 유래, 배치 적재 / 읽기 전용)
--------------------------------------------------------------------------------

-- (1) 성분 사전 (ingredients) — 파이프라인 전체의 조인 축
--     API 응답은 INGR_CODE와 INGR_KOR_NAME을 모든 행마다 함께 반복해서 내려준다.
--     INGR_CODE → INGR_KOR_NAME 이행 종속이므로 3NF에 따라 별도 테이블로 분리한다.
CREATE TABLE medivice.ingredients (
    ingredient_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ingr_code     VARCHAR(20)  UNIQUE NOT NULL,   -- 식약처 성분코드 (자연키, 예: D000581)
    name_ko       VARCHAR(200) NOT NULL,          -- 성분명(국문)
    name_en       VARCHAR(200),                   -- 성분명(영문) — UC27 한/영 전환 시 그대로 노출
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- (2) 제조사 (manufacturers)
--     ENTP_NAME이 품목 행마다 반복되므로 별도 개체로 분리한다(2NF).
CREATE TABLE medivice.manufacturers (
    manufacturer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(200) UNIQUE NOT NULL, -- 업체명
    biz_no          VARCHAR(20)                   -- 사업자등록번호 (미제공 시 NULL)
);

-- (3) 품목 (products) — 처방약·일반약·건강기능식품을 product_type으로 구분
CREATE TABLE medivice.products (
    product_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_seq        VARCHAR(20)  UNIQUE NOT NULL, -- 식약처 품목기준코드 (자연키)
    name_ko         VARCHAR(300) NOT NULL,        -- 제품명
    manufacturer_id BIGINT REFERENCES medivice.manufacturers(manufacturer_id)
                    ON DELETE SET NULL,           -- 업체가 정리되어도 품목 데이터는 보존
    product_type    VARCHAR(20)  NOT NULL,        -- ETC(전문) / OTC(일반) / SUPPLEMENT(건기식)
    chart           VARCHAR(300),                 -- 성상 (낱알식별 대조용)
    image_url       TEXT,                         -- 낱알 이미지 URL
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_type CHECK (product_type IN ('ETC','OTC','SUPPLEMENT'))
);

-- (4) 품목-성분 교차 테이블 (product_ingredients) : N:M 관계 해소
--     복합제는 한 품목에 성분이 여러 개다. 원본 API는 이를 한 칸에 묶어 내려주므로
--     1NF(한 칸에 하나의 값) 위반이며, 성분 단위 합산(UC15)이 불가능하다.
--     행으로 펼쳐야 SUM(성분별 일일 투여량)이 성립한다.
CREATE TABLE medivice.product_ingredients (
    product_id    BIGINT NOT NULL REFERENCES medivice.products(product_id)       ON DELETE CASCADE,
    ingredient_id BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    amount        NUMERIC(12,3),                  -- 1정/1캡슐당 함량 (예: 500)
    unit          VARCHAR(20),                    -- mg / mcg / IU / mL
    CONSTRAINT pk_product_ingredients PRIMARY KEY (product_id, ingredient_id)
);

-- (5) 품목 상세정보 (product_infos) — e약은요 텍스트. 품목당 1행(1:1)
--     컬럼이 크고 조회 빈도가 낮아 products에서 분리한다. UC19 AI 설명의 근거 원문.
CREATE TABLE medivice.product_infos (
    product_id   BIGINT PRIMARY KEY REFERENCES medivice.products(product_id) ON DELETE CASCADE,
    efficacy     TEXT,   -- efcyQesitm          효능
    usage_method TEXT,   -- useMethodQesitm     사용법
    warning      TEXT,   -- atpnWarnQesitm      경고
    caution      TEXT,   -- atpnQesitm          주의사항
    interaction  TEXT,   -- intrcQesitm         상호작용
    side_effect  TEXT,   -- seQesitm            부작용
    storage      TEXT    -- depositMethodQesitm 보관법
);

-- (6) DUR 금기 유형 코드 (dur_types)
--     API의 TYPE_NAME('병용금기','임부금기'…)이 문자열로 매 행 반복되므로 코드 테이블로 분리(3NF).
--     severity는 메디라이트 색 결정 규칙이 그대로 참조한다.
CREATE TABLE medivice.dur_types (
    dur_type_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(30) UNIQUE NOT NULL,      -- USJNT_TABOO, PWNM_TABOO ...
    name_ko     VARCHAR(50) NOT NULL,             -- 병용금기, 임부금기 ...
    arity       VARCHAR(10) NOT NULL,             -- SINGLE(단항) / PAIR(쌍)
    severity    VARCHAR(10) NOT NULL,             -- RED / YELLOW / INFO
    CONSTRAINT chk_dur_arity    CHECK (arity    IN ('SINGLE','PAIR')),
    -- INFO 는 '위험도'가 아니라 '판정하지 못했음'을 뜻한다. 색을 올리지 않는다.
    CONSTRAINT chk_dur_severity CHECK (severity IN ('RED','YELLOW','INFO'))
);

-- (7) 단항 DUR 규칙 (dur_single_rules) — 성분 하나에 대한 금기·주의
--     임부금기 / 특정연령대금기 / 노인주의 / 용량주의 / 투여기간주의를 한 테이블로 통합한다.
--     유형별로 테이블을 나누면 7개 테이블에 같은 구조가 반복되어 오히려 중복이 된다.
CREATE TABLE medivice.dur_single_rules (
    rule_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dur_type_id       INT    NOT NULL REFERENCES medivice.dur_types(dur_type_id),
    ingredient_id     BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    prohibit_content  TEXT,                       -- 금기 사유 원문 (PROHBT_CONTENT)
    condition_min     NUMERIC(12,3),              -- 연령 하한 / 최소 투여기간
    condition_max     NUMERIC(12,3),              -- 연령 상한 / 1일 최대용량
    condition_unit    VARCHAR(20),                -- 세 / 일 / mg
    notification_date DATE,                       -- 식약처 고시일자
    rule_version       VARCHAR(30) NOT NULL DEFAULT 'MFDS-DUR',
    source_ref         TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15059486/openapi.do',
    checked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- condition_min/max 는 NULL 이 많아 기본 UNIQUE 로는 중복이 막히지 않는다(재적재 시 행이 2배가 됐다).
    CONSTRAINT uq_single_rule UNIQUE NULLS NOT DISTINCT (dur_type_id, ingredient_id, condition_min, condition_max)
);

-- (8) 쌍 DUR 규칙 (dur_pair_rules) — 성분 두 개의 조합에 대한 금기 (병용금기·효능군중복)
--     원본 API는 (A,B)와 (B,A)를 모두 내려준다. 그대로 적재하면 100% 중복이 된다.
--     CHECK로 항상 작은 id가 왼쪽에 오도록 강제해 한 쌍이 한 행만 갖게 한다.
--     조회 시에는 LEAST/GREATEST로 맞춰 찾는다(03_medilight_views.sql 참고).
CREATE TABLE medivice.dur_pair_rules (
    pair_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dur_type_id       INT    NOT NULL REFERENCES medivice.dur_types(dur_type_id),
    ingredient_a_id   BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    ingredient_b_id   BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    prohibit_content  TEXT,
    notification_date DATE,
    rule_version       VARCHAR(30) NOT NULL DEFAULT 'MFDS-DUR',
    source_ref         TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15056780/openapi.do',
    checked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pair_order CHECK (ingredient_a_id < ingredient_b_id),
    CONSTRAINT uq_pair_rule   UNIQUE (dur_type_id, ingredient_a_id, ingredient_b_id)
);

-- (9) 효능군 (effect_groups)
--     실제 DUR '효능군중복' 응답에는 MIXTURE_* 필드가 없다. 성분 쌍이 아니라
--     성분에 붙은 분류(EFFECT_NAME '해열진통소염제', SERS_NAME '비스테로이드성 소염제')다.
--     따라서 '중복'은 데이터로 주어지지 않고 판정 시점에 계산해야 한다:
--       복용 중인 성분들의 효능군이 겹치면 중복이다.
CREATE TABLE medivice.effect_groups (
    effect_group_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(100) UNIQUE NOT NULL,  -- EFFECT_NAME (해열진통소염제 …)
    series_name     VARCHAR(200),                  -- SERS_NAME  (비스테로이드성 소염제 …)
    source_ref      TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15059486/openapi.do',
    checked_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- (10) 성분 ↔ 효능군 (ingredient_effect_groups) : N:M 교차 테이블
CREATE TABLE medivice.ingredient_effect_groups (
    ingredient_id   BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id)     ON DELETE CASCADE,
    effect_group_id INT    NOT NULL REFERENCES medivice.effect_groups(effect_group_id) ON DELETE CASCADE,
    CONSTRAINT pk_ingredient_effect_groups PRIMARY KEY (ingredient_id, effect_group_id)
);

-- (29) 성분별 1일 최대 투여량 (ingredient_daily_limits)
--     용량주의 응답에서 파생시킨 판정 전용 임계값 테이블.
--     UC15의 "일일 합산량 vs 임계값" 비교가 이 한 테이블만 보면 끝나도록 미리 정리해 둔다.
--
--     [수집 결과 확인된 제약]
--     DUR '용량주의' API에는 MAX_QTY 같은 수치 필드가 없다. 어떤 성분이 용량주의 대상인지만
--     알려 주고, 1일 최대 투여량은 주지 않는다(PROHBT_CONTENT 도 40%만 채워져 있다).
--     따라서 이 테이블은 공공 API만으로는 채울 수 없으며, 텍스트에서 뽑히는 것 + 수동 큐레이션으로 채운다.
--     임계값이 없는 성분은 v_overdose 가 LEFT JOIN 으로 처리해 '중복' 자체만으로 노랑을 띄운다.
CREATE TABLE medivice.ingredient_daily_limits (
    ingredient_id BIGINT PRIMARY KEY REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    max_qty       NUMERIC(12,3) NOT NULL,         -- 1일 최대 투여량
    unit          VARCHAR(20)   NOT NULL,
    age_group     VARCHAR(20)   NOT NULL DEFAULT 'ADULT',
    rule_version  VARCHAR(30)   NOT NULL DEFAULT 'MFDS-DUR',
    source_ref    TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15059486/openapi.do',
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_max_qty CHECK (max_qty > 0)
);


--------------------------------------------------------------------------------
-- 3. DDL : Layer B — 서비스 데이터 (사용자 유래, 런타임 CRUD)
--------------------------------------------------------------------------------

-- (28) 사용자 (users) — UC1 · UC2
--      주민등록번호는 수집하지 않는다. 판정에 쓰이는 연령은 birth_date에서 계산한다.
CREATE TABLE medivice.users (
    user_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login_id      VARCHAR(50)  UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    gender        CHAR(1),                        -- M / F
    birth_date    DATE NOT NULL,                  -- 연령금기·노인주의 판정의 근거
    lang          VARCHAR(5) NOT NULL DEFAULT 'ko', -- UC27 한/영 전환
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_gender CHECK (gender IN ('M','F')),
    CONSTRAINT chk_user_lang   CHECK (lang   IN ('ko','en'))
);

-- (11) 특이사항 (user_profiles) — UC4 · UC24. 사용자당 1행이므로 1:1 분리
CREATE TABLE medivice.user_profiles (
    user_id      BIGINT PRIMARY KEY REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    height_cm    NUMERIC(5,1),
    weight_kg    NUMERIC(5,1),                    -- 체중 기반 용량 계산의 기준
    is_pregnant  BOOLEAN,                         -- 임부금기 규칙에 직결 (선택 입력)
    renal_status VARCHAR(10),                     -- YES / NO / UNKNOWN — '전문가 확인 필요' 표시용
    drink_freq   VARCHAR(20),
    smoking      BOOLEAN,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_renal CHECK (renal_status IN ('YES','NO','UNKNOWN'))
);

-- (12) 약물 알러지 (user_allergies) : users N:M ingredients
--      "성분 코드로 저장하면 등록 시점에 즉시 대조 가능"을 그대로 구현한 교차 테이블.
CREATE TABLE medivice.user_allergies (
    user_id       BIGINT NOT NULL REFERENCES medivice.users(user_id)             ON DELETE CASCADE,
    ingredient_id BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    noted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_allergies PRIMARY KEY (user_id, ingredient_id)
);

-- (13) 지병 (user_conditions) — UC4
CREATE TABLE medivice.user_conditions (
    condition_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    CONSTRAINT uq_user_condition UNIQUE (user_id, name)
);

-- (14) 진료과 대분류 (departments)
--      UC10에서 AI가 읽고 UC11에서 미인식 시 사용자가 고르는 값.
--      처방전마다 문자열로 반복되므로 코드 테이블로 분리한다(3NF).
CREATE TABLE medivice.departments (
    department_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(50) UNIQUE NOT NULL     -- 내과, 이비인후과 ...
);

-- (15) 처방전 (prescriptions) — UC12 · UC18
--      처방전은 병원·진료과라는 맥락을 이미 갖고 있어 복용 항목의 묶음 단위가 된다.
CREATE TABLE medivice.prescriptions (
    prescription_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    hospital_name   VARCHAR(200),                 -- OCR 판독 결과
    department_id   INT REFERENCES medivice.departments(department_id) ON DELETE SET NULL,
    reason_detail   VARCHAR(200),                 -- 소분류. UC11에서 사용자가 직접 입력
    issued_date     DATE,
    source          VARCHAR(10) NOT NULL DEFAULT 'OCR',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_presc_source CHECK (source IN ('OCR','MANUAL'))
);

-- (16) 복용 항목 (medications)
--      처방약이면 prescription_id가 차고, 영양제·상비약이면 NULL이다.
--      UC13 수기 등록은 마스터 매칭이 안 되므로 product_id가 NULL이고 custom_name을 쓴다.
CREATE TABLE medivice.medications (
    medication_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    prescription_id BIGINT REFERENCES medivice.prescriptions(prescription_id) ON DELETE CASCADE,
    product_id      BIGINT REFERENCES medivice.products(product_id) ON DELETE SET NULL,
    custom_name     VARCHAR(300),                 -- UC13 수기 등록 제품명
    dose_per_intake NUMERIC(8,2) NOT NULL,        -- 1회 투여량
    times_per_day   SMALLINT     NOT NULL,        -- 1일 횟수
    as_needed       BOOLEAN      NOT NULL DEFAULT FALSE, -- 필요 시 복용이면 times_per_day는 1일 최대 횟수
    register_reason VARCHAR(200),                 -- 영양제·상비약의 자유 입력 사유
    started_at      DATE NOT NULL DEFAULT CURRENT_DATE,
    ended_at        DATE,                         -- UC14 삭제 시 종료 처리 (이력 보존)
    source          VARCHAR(10) NOT NULL DEFAULT 'OCR',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_med_source   CHECK (source IN ('OCR','MANUAL')),
    CONSTRAINT chk_med_times    CHECK (times_per_day BETWEEN 1 AND 12),
    CONSTRAINT chk_med_period   CHECK (ended_at IS NULL OR ended_at >= started_at),
    -- 마스터에 없는 약이라도 이름은 반드시 있어야 리스트에 표시할 수 있다
    CONSTRAINT chk_med_identity CHECK (product_id IS NOT NULL OR custom_name IS NOT NULL)
);

-- (17) 수기 등록 성분 (medication_ingredients)
--      마스터에 없는 품목의 성분 합산을 가능하게 하는 교차 테이블.
CREATE TABLE medivice.medication_ingredients (
    medication_id BIGINT NOT NULL REFERENCES medivice.medications(medication_id) ON DELETE CASCADE,
    ingredient_id BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id) ON DELETE CASCADE,
    amount        NUMERIC(12,3),
    unit          VARCHAR(20),
    CONSTRAINT pk_medication_ingredients PRIMARY KEY (medication_id, ingredient_id)
);

-- (18) 안전 점검 (safety_checks) — UC15 · UC16. 등록·삭제 시점마다 1행
CREATE TABLE medivice.safety_checks (
    check_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    level        VARCHAR(10) NOT NULL,            -- GREEN / YELLOW / RED (규칙 엔진이 결정)
    trigger_type VARCHAR(20) NOT NULL,            -- REGISTER / DELETE / MANUAL
    -- 판정 근거 데이터가 없어 확인하지 못한 성분 수. 색과 별개로 화면에 부기된다(UC31).
    -- 파생 가능한 값이지만, 점검 기록은 그 시점의 스냅샷이어야 하므로 저장한다.
    uncovered_count SMALLINT NOT NULL DEFAULT 0,
    amount_missing_count SMALLINT NOT NULL DEFAULT 0,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_check_level   CHECK (level        IN ('GREEN','YELLOW','RED')),
    CONSTRAINT chk_check_trigger CHECK (trigger_type IN ('REGISTER','DELETE','MANUAL')),
    CONSTRAINT chk_uncovered       CHECK (uncovered_count >= 0),
    CONSTRAINT chk_amount_missing  CHECK (amount_missing_count >= 0)
);

-- (19) 점검 상세 (safety_check_items)
--      SCR-MAIN-002는 충돌 항목만 표시하므로, 걸린 항목만 저장한다.
CREATE TABLE medivice.safety_check_items (
    item_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    check_id        BIGINT NOT NULL REFERENCES medivice.safety_checks(check_id) ON DELETE CASCADE,
    dur_type_id     INT    NOT NULL REFERENCES medivice.dur_types(dur_type_id),
    ingredient_id   BIGINT NOT NULL REFERENCES medivice.ingredients(ingredient_id),
    medication_a_id BIGINT REFERENCES medivice.medications(medication_id) ON DELETE SET NULL,
    medication_b_id BIGINT REFERENCES medivice.medications(medication_id) ON DELETE SET NULL,
    total_amount    NUMERIC(12,3),               -- 일일 합산량
    threshold       NUMERIC(12,3),               -- 비교한 임계값
    unit            VARCHAR(20),
    reason_code     VARCHAR(40) NOT NULL,
    source_ref      TEXT,
    level           VARCHAR(10) NOT NULL,
    -- INFO = 판정 근거가 없어 확인하지 못한 성분 (UC31). YELLOW/RED와 같은 줄에 두지 않는다.
    CONSTRAINT chk_item_level CHECK (level IN ('YELLOW','RED','INFO'))
);

-- (20) AI 산출물 (ai_outputs) — UC17 · UC19 · UC29
--      대상이 셋(판정 설명 / 약물 설명 / 보고서)이므로 target_type + target_id로 가리킨다.
--      status는 응답 지연·실패라는 예외 흐름을 그대로 표현한다.
CREATE TABLE medivice.ai_outputs (
    ai_output_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type  VARCHAR(20) NOT NULL,           -- SAFETY_CHECK / MEDICATION / REPORT
    target_id    BIGINT      NOT NULL,
    prompt       TEXT        NOT NULL,
    result_json  JSONB,
    status       VARCHAR(10) NOT NULL DEFAULT 'pending',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_target CHECK (target_type IN ('SAFETY_CHECK','MEDICATION','REPORT')),
    CONSTRAINT chk_ai_status CHECK (status      IN ('pending','processing','completed','failed'))
);

-- (21) 증상 마스터 (symptoms)
--      SCR-SE-001 체크박스 문구가 기록마다 반복되지 않도록 코드로 분리한다(3NF).
CREATE TABLE medivice.symptoms (
    symptom_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category   VARCHAR(30)  NOT NULL,            -- 소화기 / 피부 / 전신·통증 / 신경·감각 / 수면·정신 / 기타
    name       VARCHAR(100) NOT NULL,
    CONSTRAINT uq_symptom UNIQUE (category, name)
);

-- (22) 부작용 기록 (side_effect_logs) — UC20
--      투약 시간대는 받지 않는다. 발생일과 작성 시각만 받는다.
CREATE TABLE medivice.side_effect_logs (
    log_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    occurred_date DATE        NOT NULL,          -- 미래 날짜 불가 (예외 흐름)
    written_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    note          TEXT,                          -- 예외사항 자유 입력
    CONSTRAINT chk_log_not_future CHECK (occurred_date <= CURRENT_DATE)
);

-- (23) 기록-증상 교차 테이블 (side_effect_symptoms) : 증상 다중 선택 N:M 해소
CREATE TABLE medivice.side_effect_symptoms (
    log_id     BIGINT NOT NULL REFERENCES medivice.side_effect_logs(log_id) ON DELETE CASCADE,
    symptom_id INT    NOT NULL REFERENCES medivice.symptoms(symptom_id),
    CONSTRAINT pk_side_effect_symptoms PRIMARY KEY (log_id, symptom_id)
);

-- (24) 복용 스냅샷 (side_effect_snapshots) — UC21
--      그 날짜에 복용 중이던 항목을 저장 시점에 붙인다. 사용자는 약을 고르지 않는다.
--      medications가 나중에 삭제돼도 기록은 남아야 하므로 이름·성분을 값으로 복사해 둔다.
--      (참조 무결성은 SET NULL로 풀되, 표시용 텍스트는 스냅샷으로 보존)
CREATE TABLE medivice.side_effect_snapshots (
    snapshot_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    log_id          BIGINT NOT NULL REFERENCES medivice.side_effect_logs(log_id) ON DELETE CASCADE,
    medication_id   BIGINT REFERENCES medivice.medications(medication_id) ON DELETE SET NULL,
    product_name    VARCHAR(300) NOT NULL,
    ingredient_text TEXT
);

-- (25) 보고서 (reports) — UC28 · UC29
CREATE TABLE medivice.reports (
    report_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    language     VARCHAR(5)  NOT NULL DEFAULT 'ko',
    status       VARCHAR(10) NOT NULL DEFAULT 'pending',
    result_json  JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_report_period CHECK (period_start <= period_end),
    CONSTRAINT chk_report_lang   CHECK (language IN ('ko','en')),
    CONSTRAINT chk_report_status CHECK (status   IN ('pending','processing','completed','failed'))
);

-- (26) 가족 계정 연동 (family_links) — UC30 : users의 자기참조 N:M
CREATE TABLE medivice.family_links (
    from_user_id BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    to_user_id   BIGINT NOT NULL REFERENCES medivice.users(user_id) ON DELETE CASCADE,
    status       VARCHAR(10) NOT NULL DEFAULT 'INVITED',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_family_links PRIMARY KEY (from_user_id, to_user_id),
    CONSTRAINT chk_family_self   CHECK (from_user_id <> to_user_id),
    CONSTRAINT chk_family_status CHECK (status IN ('INVITED','ACCEPTED','REJECTED'))
);


-- (27) 안내 문구 템플릿 (notice_templates) — UC31
--      판정 불가 안내는 AI가 생성하지 않는다. AI에 맡기면 "괜찮을 것 같습니다" 같은
--      완화된 표현이 나올 수 있고, 안전 고지는 문장이 흔들리면 안 되기 때문이다.
--      UC27 한/영 전환이 있으므로 언어를 행으로 분리한다(문구를 코드에 하드코딩하지 않는다).
CREATE TABLE medivice.notice_templates (
    notice_code VARCHAR(40) NOT NULL,             -- UNCOVERED_INGREDIENT 등
    lang        VARCHAR(5)  NOT NULL,             -- ko / en
    message     TEXT        NOT NULL,             -- {count}, {ingredients} 치환 자리를 포함
    CONSTRAINT pk_notice_templates PRIMARY KEY (notice_code, lang),
    CONSTRAINT chk_notice_lang CHECK (lang IN ('ko','en'))
);
