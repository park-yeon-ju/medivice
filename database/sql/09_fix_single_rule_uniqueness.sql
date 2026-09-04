-- 작성목적 : dur_single_rules 재적재 시 행이 계속 늘어나는 문제를 해결한다.
--            uq_single_rule 은 condition_min/condition_max 가 NULL 인 규칙(대부분의 임부·연령 금기)에서
--            NULL != NULL 규칙 때문에 중복을 막지 못했고, load_postgres.py 를 두 번 돌리면 행이 2배가 됐다.
--            PostgreSQL 15+ 의 NULLS NOT DISTINCT 로 NULL 조합도 하나의 키로 취급한다.
-- 전제     : PostgreSQL 15 이상 (현재 서버 16.x)
-- 순서     : 기존 중복 정리 → 제약 재생성

DELETE FROM medivice.dur_single_rules a
 USING medivice.dur_single_rules b
 WHERE a.rule_id > b.rule_id
   AND a.dur_type_id = b.dur_type_id
   AND a.ingredient_id = b.ingredient_id
   AND a.condition_min IS NOT DISTINCT FROM b.condition_min
   AND a.condition_max IS NOT DISTINCT FROM b.condition_max;

ALTER TABLE medivice.dur_single_rules DROP CONSTRAINT IF EXISTS uq_single_rule;
ALTER TABLE medivice.dur_single_rules
  ADD CONSTRAINT uq_single_rule UNIQUE NULLS NOT DISTINCT
      (dur_type_id, ingredient_id, condition_min, condition_max);
