/*
작성자 : 박기준
작성목적 : 실제 식약처 데이터로 적재된 DB에서, 세 종류의 판정이 모두 작동함을 보인다.
          ① 병용금기(RED)  ② 성분 중복(YELLOW)  ③ 판정 불가 성분 안내(UC31)
          정규화한 스키마 + 판정 뷰가 실제 공공 데이터 위에서 도는 증빙이다(설계문서 §3).
작성일 : 2026-09-02
전제    : setup_db.py --all 또는 load_postgres.py 로 실데이터가 적재되어 있어야 한다.

시나리오
  50대 여성 사용자가 아래 4개를 복용 중이라고 가정한다. 모두 실제 품목기준코드다.
    - 심바로드정 20mg      (심바스타틴)        ← 병원 처방
    - 이트라정 100mg       (이트라코나졸)       ← 병원 처방  → 심바스타틴과 병용금기(횡문근융해증)
    - 셀코나졸정 100mg     (이트라코나졸)       ← 다른 제품  → 이트라코나졸 성분 중복
    - 대원아미노필린정      (아미노필린)         ← 임부금기 성분(이 사용자는 비임신이라 미발동)
*/

SET search_path TO medivice, public;

--------------------------------------------------------------------------------
-- 1. 시나리오 데이터 입력 (반복 실행 가능)
--------------------------------------------------------------------------------
DELETE FROM medivice.users WHERE login_id = 'demo_user';

INSERT INTO medivice.users (login_id, password_hash, gender, birth_date)
VALUES ('demo_user', 'hashed_pw_placeholder', 'F', '1972-05-14');

INSERT INTO medivice.user_profiles (user_id, height_cm, weight_kg, is_pregnant, renal_status)
SELECT user_id, 162.0, 58.0, FALSE, 'UNKNOWN' FROM medivice.users WHERE login_id = 'demo_user';

-- 처방전 : ○○내과 · 내과
INSERT INTO medivice.prescriptions (user_id, hospital_name, department_id, reason_detail, issued_date, source)
SELECT u.user_id, '○○내과', d.department_id, '고지혈증·무좀 동반 치료', CURRENT_DATE - 3, 'OCR'
  FROM medivice.users u, medivice.departments d
 WHERE u.login_id = 'demo_user' AND d.name = '내과';

-- 복용 항목 4건. product_id 는 실제 item_seq 로 매칭한다.
--   함량이 없어 등록이 막히지 않도록, 매칭 실패 시 custom_name 으로도 넣는다.
INSERT INTO medivice.medications
  (user_id, prescription_id, product_id, custom_name, dose_per_intake, times_per_day, register_reason, source)
SELECT u.user_id, p.prescription_id, pr.product_id, pr.name_ko, 1, 1, '고지혈증', 'OCR'
  FROM medivice.users u
  JOIN medivice.prescriptions p ON p.user_id = u.user_id
  LEFT JOIN medivice.products pr ON pr.item_seq = '200200173'   -- 심바로드정 20mg (심바스타틴)
 WHERE u.login_id = 'demo_user';

INSERT INTO medivice.medications
  (user_id, prescription_id, product_id, custom_name, dose_per_intake, times_per_day, register_reason, source)
SELECT u.user_id, p.prescription_id, pr.product_id, pr.name_ko, 1, 1, '무좀', 'OCR'
  FROM medivice.users u
  JOIN medivice.prescriptions p ON p.user_id = u.user_id
  LEFT JOIN medivice.products pr ON pr.item_seq = '200003111'   -- 이트라정 100mg (이트라코나졸)
 WHERE u.login_id = 'demo_user';

INSERT INTO medivice.medications
  (user_id, product_id, custom_name, dose_per_intake, times_per_day, register_reason, source)
SELECT u.user_id, pr.product_id, pr.name_ko, 1, 1, '무좀(다른 약국)', 'MANUAL'
  FROM medivice.users u
  LEFT JOIN medivice.products pr ON pr.item_seq = '201405797'   -- 셀코나졸정 100mg (이트라코나졸)
 WHERE u.login_id = 'demo_user';

INSERT INTO medivice.medications
  (user_id, product_id, custom_name, dose_per_intake, times_per_day, register_reason, source)
SELECT u.user_id, pr.product_id, pr.name_ko, 1, 2, '천식', 'MANUAL'
  FROM medivice.users u
  LEFT JOIN medivice.products pr ON pr.item_seq = '197000102'   -- 대원아미노필린정 (임부금기 성분)
 WHERE u.login_id = 'demo_user';

--------------------------------------------------------------------------------
-- 2. 판정 결과 확인
--------------------------------------------------------------------------------
\echo '--- (1) 복용 항목을 성분 단위로 전개 ---'
SELECT COALESCE(p.name_ko, m.custom_name) AS 제품, i.name_ko AS 성분,
       v.daily_amount AS 일일투여량, v.unit
  FROM medivice.v_active_ingredients v
  JOIN medivice.medications m ON m.medication_id = v.medication_id
  LEFT JOIN medivice.products p ON p.product_id = m.product_id
  JOIN medivice.ingredients i ON i.ingredient_id = v.ingredient_id
 ORDER BY 성분;

\echo '--- (2) 병용금기 (RED) — 심바스타틴 × 이트라코나졸 ---'
SELECT ma.custom_name AS 약A, mb.custom_name AS 약B, c.level AS 판정, c.prohibit_content AS 사유
  FROM medivice.v_pair_conflict c
  JOIN medivice.medications ma ON ma.medication_id = c.medication_a_id
  JOIN medivice.medications mb ON mb.medication_id = c.medication_b_id
  JOIN medivice.users u ON u.user_id = c.user_id
 WHERE u.login_id = 'demo_user';

\echo '--- (3) 성분 중복 (YELLOW) — 이트라코나졸이 두 제품에 ---'
SELECT o.name_ko AS 성분, o.med_count AS 겹친제품수, o.total_daily AS 합산량, o.level AS 판정
  FROM medivice.v_overdose o
  JOIN medivice.users u ON u.user_id = o.user_id
 WHERE u.login_id = 'demo_user';

\echo '--- (4) 사용자 조건 금기 (임부·연령·노인) — 비임신이라 아미노필린 미발동 ---'
SELECT i.name_ko AS 성분, sc.level AS 판정, sc.prohibit_content AS 사유
  FROM medivice.v_single_conflict sc
  JOIN medivice.ingredients i ON i.ingredient_id = sc.ingredient_id
  JOIN medivice.users u ON u.user_id = sc.user_id
 WHERE u.login_id = 'demo_user';

\echo '--- (5) 판정 근거가 없는 성분 (초록과 구분) ---'
SELECT c.name_ko AS 성분, c.ingr_code AS 성분코드
  FROM medivice.v_uncovered_ingredients c
  JOIN medivice.users u ON u.user_id = c.user_id
 WHERE u.login_id = 'demo_user';

\echo '--- (6) 최종 메디라이트 색 (UC16) — 병용금기가 있으므로 RED ---'
SELECT u.login_id, v.medilight_level AS 메디라이트, v.uncovered_count AS 판정불가성분수
  FROM medivice.v_medilight v JOIN medivice.users u ON u.user_id = v.user_id
 WHERE u.login_id = 'demo_user';

\echo '--- (7) 사용자에게 출력되는 안내 문구 (UC31) ---'
SELECT n.medilight_level AS 색, n.notice_message AS 안내문구
  FROM medivice.v_safety_notice n
  JOIN medivice.users u ON u.user_id = n.user_id
 WHERE u.login_id = 'demo_user';
