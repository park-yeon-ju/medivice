/*
작성자 : 박기준
작성목적 : 메디바이스 코드 테이블 시드 및 판정 경로 인덱스 생성
작성일 : 2026-09-02
활용 파일 : 01_schema_ddl.sql 실행 후 이어서 실행

변경사항 내역
2026-09-02 - 최초 작성 - DUR 유형 7종, 진료과 대분류 13종, 증상 마스터 18종 시드 +
             medications → ingredients → dur_* 판정 경로 인덱스 8종 생성
*/

SET search_path TO medivice, public;

--------------------------------------------------------------------------------
-- 1. 코드 테이블 시드
--------------------------------------------------------------------------------

-- (1) DUR 금기 유형 — 식약처 DUR 품목정보 7종
INSERT INTO medivice.dur_types (code, name_ko, arity, severity) VALUES
    ('USJNT_TABOO',    '병용금기',       'PAIR',   'RED'),
    ('EFCY_DPLCT',     '효능군중복',     'PAIR',   'YELLOW'),
    ('PWNM_TABOO',     '임부금기',       'SINGLE', 'RED'),
    ('AGE_TABOO',      '특정연령대금기', 'SINGLE', 'RED'),
    ('CPCTY_ATENT',    '용량주의',       'SINGLE', 'YELLOW'),
    ('MDCTN_PD_ATENT', '투여기간주의',   'SINGLE', 'YELLOW'),
    ('ODSN_ATENT',     '노인주의',       'SINGLE', 'YELLOW'),
    -- 규칙이 아니라 '규칙이 없음'을 나타내는 유형. 색을 올리지 않고 안내만 띄운다(UC31).
    ('NO_DUR_DATA',    '판정 근거 없음', 'SINGLE', 'INFO')
ON CONFLICT (code) DO NOTHING;

-- (2) 진료과 대분류 — UC10 AI 판독 대상 / UC11 미인식 시 선택 목록
INSERT INTO medivice.departments (name) VALUES
    ('내과'), ('이비인후과'), ('정형외과'), ('피부과'), ('안과'), ('치과'), ('산부인과'),
    ('소아청소년과'), ('신경과'), ('정신건강의학과'), ('비뇨의학과'), ('가정의학과'), ('기타')
ON CONFLICT (name) DO NOTHING;

-- (3) 부작용 증상 마스터 — SCR-SE-001 체크박스 6분류
INSERT INTO medivice.symptoms (category, name) VALUES
    ('소화기',     '메스꺼움'), ('소화기',     '구토'),     ('소화기',     '설사'), ('소화기', '복통'),
    ('피부',       '발진'),     ('피부',       '가려움'),   ('피부',       '두드러기'),
    ('전신·통증',  '두통'),     ('전신·통증',  '발열'),     ('전신·통증',  '근육통'), ('전신·통증', '피로'),
    ('신경·감각',  '어지러움'), ('신경·감각',  '이명'),     ('신경·감각',  '시야흐림'),
    ('수면·정신',  '불면'),     ('수면·정신',  '졸림'),     ('수면·정신',  '불안'),
    ('기타',       '기타')
ON CONFLICT (category, name) DO NOTHING;

-- (4) 안내 문구 템플릿 — UC31. 서비스는 이 문장을 그대로 출력한다(AI 생성 아님).
--     '안전합니다'라고 말하지 않는 것이 이 문구의 핵심이다.
INSERT INTO medivice.notice_templates (notice_code, lang, message) VALUES
    ('UNCOVERED_INGREDIENT', 'ko',
     '복용 목록의 성분 중 {count}개는 안전성 판정에 필요한 공공 데이터가 없어 확인하지 못했습니다: {ingredients}. '
     '문제가 없다는 뜻이 아니라 확인하지 못했다는 뜻이므로, 해당 성분에 대해서는 의사 또는 약사와 상담하시기 바랍니다.'),
    ('UNCOVERED_INGREDIENT', 'en',
     '{count} ingredient(s) in your medication list could not be checked because reference data is unavailable: {ingredients}. '
     'This does not mean they are safe — it means they were not checked. Please consult your doctor or pharmacist about them.'),
    ('AMOUNT_MISSING', 'ko',
     '{count}개 성분은 함량 정보가 없어 일일 섭취량을 합산하지 못했습니다: {ingredients}. 복용량은 직접 확인해 주세요.'),
    ('AMOUNT_MISSING', 'en',
     'Daily totals could not be calculated for {count} ingredient(s) due to missing dosage data: {ingredients}. '
     'Please verify your dosage directly.')
ON CONFLICT (notice_code, lang) DO NOTHING;

--------------------------------------------------------------------------------
-- 2. 인덱스 — medications → ingredients → dur_* 판정 경로에 맞춰 생성
--------------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_prod_ingr_ingredient ON medivice.product_ingredients(ingredient_id);
CREATE INDEX IF NOT EXISTS idx_pair_ingr_a          ON medivice.dur_pair_rules(ingredient_a_id);
CREATE INDEX IF NOT EXISTS idx_pair_ingr_b          ON medivice.dur_pair_rules(ingredient_b_id);
CREATE INDEX IF NOT EXISTS idx_single_ingr          ON medivice.dur_single_rules(ingredient_id);
-- 부분 인덱스: 판정은 항상 '복용 중'인 항목만 본다
CREATE INDEX IF NOT EXISTS idx_med_user_active      ON medivice.medications(user_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_check_user_time      ON medivice.safety_checks(user_id, checked_at DESC);
CREATE INDEX IF NOT EXISTS idx_selog_user_date      ON medivice.side_effect_logs(user_id, occurred_date DESC);
CREATE INDEX IF NOT EXISTS idx_ai_target            ON medivice.ai_outputs(target_type, target_id);
