-- 작성목적 : 식약처 제품 허가정보 전량(43,019품목) 적재 시 기존 컬럼 폭이 부족해 발생하는
--            value too long 오류를 해소한다. 이름을 자르지 않고 컬럼을 넓히는 방향으로 처리한다.
--            varchar 확장은 PostgreSQL에서 테이블 재작성 없이 수행된다.
-- 실행     : 뷰가 name_ko를 참조하므로 뷰를 먼저 내리고, 확장 후 sql/03_medilight_views.sql 을 다시 실행한다.
-- 근거     : products.name_ko 실측 최대 391자, ingredients.name_ko 실측 최대 287자

DROP VIEW IF EXISTS medivice.v_medilight_api CASCADE;
DROP VIEW IF EXISTS medivice.v_safety_notice CASCADE;
DROP VIEW IF EXISTS medivice.v_medilight CASCADE;
DROP VIEW IF EXISTS medivice.v_amount_missing_ingredients CASCADE;
DROP VIEW IF EXISTS medivice.v_uncovered_ingredients CASCADE;
DROP VIEW IF EXISTS medivice.v_effect_dup CASCADE;
DROP VIEW IF EXISTS medivice.v_single_conflict CASCADE;
DROP VIEW IF EXISTS medivice.v_pair_conflict CASCADE;
DROP VIEW IF EXISTS medivice.v_overdose CASCADE;
DROP VIEW IF EXISTS medivice.v_active_ingredients CASCADE;

ALTER TABLE medivice.products    ALTER COLUMN name_ko TYPE VARCHAR(500);
ALTER TABLE medivice.ingredients ALTER COLUMN name_ko TYPE VARCHAR(300);
