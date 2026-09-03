/*
작성자 : 박기준
작성목적 : UC15(성분 충돌·중복 판정) → UC16(메디라이트 색 표시) 실행 경로를 뷰로 구현.
          정규화한 스키마가 실제 판정을 지탱하는지 증명하는 근거 쿼리이기도 하다.
작성일 : 2026-09-02
활용 파일 : 01_schema_ddl.sql, 02_seed_code.sql 실행 후 이어서 실행

변경사항 내역
2026-09-02 - 최초 작성 - v_active_ingredients(성분 단위 전개), v_overdose(용량 초과),
             v_pair_conflict(병용금기), v_single_conflict(임부·연령·노인), v_medilight(최종 색) 5종 작성
*/

SET search_path TO medivice, public;

-- 뷰는 컬럼 타입이 바뀌면 CREATE OR REPLACE 가 실패하므로 의존 역순으로 먼저 정리한다.
DROP VIEW IF EXISTS medivice.v_safety_notice    CASCADE;
DROP VIEW IF EXISTS medivice.v_effect_dup      CASCADE;
DROP VIEW IF EXISTS medivice.v_medilight        CASCADE;
DROP VIEW IF EXISTS medivice.v_uncovered_ingredients CASCADE;
DROP VIEW IF EXISTS medivice.v_single_conflict  CASCADE;
DROP VIEW IF EXISTS medivice.v_pair_conflict    CASCADE;
DROP VIEW IF EXISTS medivice.v_overdose         CASCADE;
DROP VIEW IF EXISTS medivice.v_active_ingredients CASCADE;

--------------------------------------------------------------------------------
-- (1) 복용 중인 항목을 '성분 단위'로 전개한다 — 모든 판정의 출발점
--     마스터 매칭분(product_ingredients)과 수기 등록분(medication_ingredients)을 UNION 한다.
--     일일 투여량 = 1정당 함량 × 1회 투여량 × 1일 횟수
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_active_ingredients AS
SELECT m.user_id,
       m.medication_id,
       pi.ingredient_id,
       pi.amount * m.dose_per_intake * m.times_per_day AS daily_amount,
       pi.unit
  FROM medivice.medications m
  JOIN medivice.product_ingredients pi ON pi.product_id = m.product_id
 WHERE m.ended_at IS NULL
UNION ALL
SELECT m.user_id,
       m.medication_id,
       mi.ingredient_id,
       mi.amount * m.dose_per_intake * m.times_per_day AS daily_amount,
       mi.unit
  FROM medivice.medications m
  JOIN medivice.medication_ingredients mi ON mi.medication_id = m.medication_id
 WHERE m.ended_at IS NULL;

--------------------------------------------------------------------------------
-- (2) 성분 중복 + 일일 상한 초과 (용량주의)
--     "아세트아미노펜이 두 제품에 겹칩니다"라는 노랑 사유가 여기서 나온다.
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_overdose AS
SELECT v.user_id,
       v.ingredient_id,
       i.name_ko,
       COUNT(DISTINCT v.medication_id) AS med_count,     -- 몇 개 제품에 겹치는가
       SUM(v.daily_amount)             AS total_daily,   -- 일일 합산량
       l.max_qty,
       COALESCE(l.unit, MIN(v.unit))::VARCHAR(20) AS unit,
       CASE WHEN l.max_qty IS NOT NULL AND SUM(v.daily_amount) > l.max_qty
            THEN 'RED' ELSE 'YELLOW'
       END AS level
  FROM medivice.v_active_ingredients    v
  JOIN medivice.ingredients             i ON i.ingredient_id = v.ingredient_id
  -- 상한이 아직 수집되지 않은 성분이라도 '중복' 자체는 잡아야 하므로 LEFT JOIN 한다
  LEFT JOIN medivice.ingredient_daily_limits l ON l.ingredient_id = v.ingredient_id
 GROUP BY v.user_id, v.ingredient_id, i.name_ko, l.max_qty, l.unit
-- 노랑 조건 두 가지 : ① 같은 성분이 두 제품 이상에 들어 있다  ② 상한의 80%에 근접했다
HAVING COUNT(DISTINCT v.medication_id) >= 2
    OR (l.max_qty IS NOT NULL AND SUM(v.daily_amount) >= l.max_qty * 0.8);

--------------------------------------------------------------------------------
-- (3) 병용금기 · 효능군중복 — 서로 다른 두 복용 항목의 성분 쌍이 규칙에 걸리는가
--     저장 규칙이 (a<b) 한 방향이므로 조회도 LEAST/GREATEST로 맞춰 준다.
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_pair_conflict AS
SELECT a.user_id,
       a.medication_id AS medication_a_id,
       b.medication_id AS medication_b_id,
       r.dur_type_id,
       r.prohibit_content,
       t.severity AS level
  FROM medivice.v_active_ingredients a
  JOIN medivice.v_active_ingredients b
    ON a.user_id = b.user_id
   AND a.medication_id < b.medication_id            -- 같은 약끼리 자기 자신 비교 방지
  JOIN medivice.dur_pair_rules r
    ON r.ingredient_a_id = LEAST   (a.ingredient_id, b.ingredient_id)
   AND r.ingredient_b_id = GREATEST(a.ingredient_id, b.ingredient_id)
  JOIN medivice.dur_types t ON t.dur_type_id = r.dur_type_id;

--------------------------------------------------------------------------------
-- (4) 사용자 조건 금기 — 임부금기 · 특정연령대금기 · 노인주의
--     users.birth_date와 user_profiles.is_pregnant가 판정 조건으로 직접 쓰인다.
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_single_conflict AS
SELECT v.user_id,
       v.medication_id,
       v.ingredient_id,
       r.dur_type_id,
       r.prohibit_content,
       t.severity AS level
  FROM medivice.v_active_ingredients v
  JOIN medivice.dur_single_rules  r ON r.ingredient_id = v.ingredient_id
  JOIN medivice.dur_types         t ON t.dur_type_id   = r.dur_type_id
  JOIN medivice.users             u ON u.user_id       = v.user_id
  LEFT JOIN medivice.user_profiles p ON p.user_id      = u.user_id
 WHERE (t.code = 'PWNM_TABOO' AND p.is_pregnant IS TRUE)
    OR (t.code = 'AGE_TABOO'
        AND EXTRACT(YEAR FROM age(u.birth_date))
            BETWEEN COALESCE(r.condition_min, 0) AND COALESCE(r.condition_max, 200))
    OR (t.code = 'ODSN_ATENT' AND EXTRACT(YEAR FROM age(u.birth_date)) >= 65);

--------------------------------------------------------------------------------
-- (4-1) 효능군 중복 — 서로 다른 두 약이 같은 효능군에 속하는가
--     DUR 효능군중복 응답은 '성분 쌍'이 아니라 '성분 → 효능군' 분류다.
--     그래서 중복 여부는 데이터가 알려 주는 것이 아니라 여기서 계산한다.
--     (예: 해열진통소염제 계열 두 가지를 동시에 복용 중)
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_effect_dup AS
SELECT a.user_id,
       a.medication_id AS medication_a_id,
       b.medication_id AS medication_b_id,
       e.effect_group_id,
       e.name AS effect_name,
       'YELLOW'::VARCHAR(10) AS level
  FROM medivice.v_active_ingredients a
  JOIN medivice.v_active_ingredients b
    ON a.user_id = b.user_id AND a.medication_id < b.medication_id
  JOIN medivice.ingredient_effect_groups ea ON ea.ingredient_id = a.ingredient_id
  JOIN medivice.ingredient_effect_groups eb ON eb.ingredient_id = b.ingredient_id
   AND eb.effect_group_id = ea.effect_group_id
  JOIN medivice.effect_groups e ON e.effect_group_id = ea.effect_group_id
 WHERE a.ingredient_id <> b.ingredient_id;   -- 같은 성분 중복은 v_overdose 가 잡는다

--------------------------------------------------------------------------------
-- (5) 판정 근거가 없는 성분 — '초록'과 '확인 못 함'을 구분하기 위한 뷰
--     복용 중인 성분 가운데 DUR 규칙에도, 일일 상한 테이블에도 걸리지 않는 것들이다.
--     이런 성분만 있는 사용자에게 그냥 초록을 띄우면
--     "확인된 문제 없음"과 "확인하지 못했음"이 같은 색으로 보인다 — 복약 안전 서비스에서
--     가장 위험한 실패 방식이므로, 화면에 함께 표시할 수 있도록 세어 둔다.
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_uncovered_ingredients AS
SELECT DISTINCT
       v.user_id,
       v.medication_id,
       v.ingredient_id,
       i.name_ko,
       i.name_en,
       i.ingr_code,
       (v.daily_amount IS NULL) AS amount_missing   -- 함량조차 없으면 합산 자체가 불가능하다
  FROM medivice.v_active_ingredients v
  JOIN medivice.ingredients i ON i.ingredient_id = v.ingredient_id
 WHERE NOT EXISTS (SELECT 1 FROM medivice.dur_single_rules r
                    WHERE r.ingredient_id = v.ingredient_id)
   AND NOT EXISTS (SELECT 1 FROM medivice.dur_pair_rules r
                    WHERE v.ingredient_id IN (r.ingredient_a_id, r.ingredient_b_id))
   AND NOT EXISTS (SELECT 1 FROM medivice.ingredient_daily_limits l
                    WHERE l.ingredient_id = v.ingredient_id)
   AND NOT EXISTS (SELECT 1 FROM medivice.ingredient_effect_groups g
                    WHERE g.ingredient_id = v.ingredient_id);

--------------------------------------------------------------------------------
-- (6) 최종 메디라이트 색 — 하나라도 RED면 RED, YELLOW가 있으면 YELLOW, 없으면 GREEN
--     색은 규칙 엔진이 정하고 AI는 문장만 만든다. 이 뷰가 그 '규칙 엔진'에 해당한다.
--     uncovered_count 는 색을 바꾸지 않는다. 화면에 부기하기 위한 값이다.
--     ("성분 N개는 판정 근거 데이터가 없습니다")
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_medilight AS
SELECT u.user_id,
       CASE COALESCE(MAX(CASE x.level WHEN 'RED' THEN 3 WHEN 'YELLOW' THEN 2 END), 1)
            WHEN 3 THEN 'RED' WHEN 2 THEN 'YELLOW' ELSE 'GREEN'
       END AS medilight_level,
       COALESCE((SELECT COUNT(*) FROM medivice.v_uncovered_ingredients c
                  WHERE c.user_id = u.user_id), 0) AS uncovered_count
  FROM medivice.users u
  LEFT JOIN (
        SELECT user_id, level FROM medivice.v_overdose
        UNION ALL
        SELECT user_id, level FROM medivice.v_pair_conflict
        UNION ALL
        SELECT user_id, level FROM medivice.v_effect_dup
        UNION ALL
        SELECT user_id, level FROM medivice.v_single_conflict
       ) x ON x.user_id = u.user_id
 GROUP BY u.user_id;

--------------------------------------------------------------------------------
-- (7) 사용자에게 출력할 안내 문구 (UC31)
--     색 + 판정 불가 성분 안내를 한 행으로 만든다.
--     문장은 notice_templates 에서 가져와 성분명만 끼워 넣는다 — AI를 거치지 않는다.
--     사용자의 lang 설정(UC27)에 따라 한/영이 자동으로 바뀐다.
--------------------------------------------------------------------------------
CREATE OR REPLACE VIEW medivice.v_safety_notice AS
SELECT u.user_id,
       u.lang,
       m.medilight_level,
       m.uncovered_count,
       CASE
         WHEN m.uncovered_count = 0 THEN NULL
         ELSE replace(
                replace(t.message, '{count}', m.uncovered_count::TEXT),
                '{ingredients}', c.names)
       END AS notice_message
  FROM medivice.users       u
  JOIN medivice.v_medilight m ON m.user_id = u.user_id
  LEFT JOIN LATERAL (
        -- UC27: 영문 표시일 때는 영문 성분명을 쓴다. 없으면 국문으로 폴백한다.
        SELECT string_agg(DISTINCT CASE WHEN u.lang = 'en'
                                        THEN COALESCE(x.name_en, x.name_ko)
                                        ELSE x.name_ko END, ', ') AS names
          FROM medivice.v_uncovered_ingredients x
         WHERE x.user_id = u.user_id
       ) c ON TRUE
  LEFT JOIN medivice.notice_templates t
         ON t.notice_code = 'UNCOVERED_INGREDIENT'
        AND t.lang = u.lang;
