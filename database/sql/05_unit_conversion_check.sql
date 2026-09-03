-- 실행 대상: 검증용 DB. 모든 변경은 마지막에 ROLLBACK 된다.
-- 400 mg + 0.7 g = 1,100 mg, 상한 1 g = 1,000 mg이므로 RED여야 한다.
BEGIN;

INSERT INTO medivice.ingredients (ingr_code, name_ko)
VALUES ('__UNIT_CHECK__', '단위 변환 검증 성분');

INSERT INTO medivice.ingredient_daily_limits (ingredient_id, max_qty, unit)
SELECT ingredient_id, 1, 'g'
  FROM medivice.ingredients
 WHERE ingr_code = '__UNIT_CHECK__';

INSERT INTO medivice.users (login_id, password_hash, birth_date)
VALUES ('__unit_check__', 'not-a-real-password', DATE '1990-01-01');

WITH new_medication AS (
    INSERT INTO medivice.medications
           (user_id, custom_name, dose_per_intake, times_per_day, source)
    SELECT user_id, '400 mg 검증 항목', 1, 1, 'MANUAL'
      FROM medivice.users WHERE login_id = '__unit_check__'
    RETURNING medication_id
)
INSERT INTO medivice.medication_ingredients (medication_id, ingredient_id, amount, unit)
SELECT m.medication_id, i.ingredient_id, 400, 'mg'
  FROM new_medication m
 CROSS JOIN medivice.ingredients i
 WHERE i.ingr_code = '__UNIT_CHECK__';

WITH new_medication AS (
    INSERT INTO medivice.medications
           (user_id, custom_name, dose_per_intake, times_per_day, source)
    SELECT user_id, '0.7 g 검증 항목', 1, 1, 'MANUAL'
      FROM medivice.users WHERE login_id = '__unit_check__'
    RETURNING medication_id
)
INSERT INTO medivice.medication_ingredients (medication_id, ingredient_id, amount, unit)
SELECT m.medication_id, i.ingredient_id, 0.7, 'g'
  FROM new_medication m
 CROSS JOIN medivice.ingredients i
 WHERE i.ingr_code = '__UNIT_CHECK__';

DO $$
DECLARE
    result medivice.v_overdose%ROWTYPE;
BEGIN
    SELECT o.* INTO result
      FROM medivice.v_overdose o
      JOIN medivice.users u ON u.user_id = o.user_id
     WHERE u.login_id = '__unit_check__';

    IF result.total_daily <> 1100 OR result.max_qty <> 1000
       OR result.unit <> 'mg' OR result.level <> 'RED' THEN
        RAISE EXCEPTION '단위 변환 실패: total=%, max=%, unit=%, level=%',
            result.total_daily, result.max_qty, result.unit, result.level;
    END IF;
END $$;

-- 농도 정보가 없는 부피(mL)는 mg 상한과 억지로 비교하지 않아야 한다.
INSERT INTO medivice.ingredients (ingr_code, name_ko)
VALUES ('__UNIT_VOL__', '부피 단위 검증 성분');

INSERT INTO medivice.ingredient_daily_limits (ingredient_id, max_qty, unit)
SELECT ingredient_id, 100, 'mg'
  FROM medivice.ingredients
 WHERE ingr_code = '__UNIT_VOL__';

WITH new_medication AS (
    INSERT INTO medivice.medications
           (user_id, custom_name, dose_per_intake, times_per_day, source)
    SELECT user_id, '200 mL 검증 항목', 1, 1, 'MANUAL'
      FROM medivice.users WHERE login_id = '__unit_check__'
    RETURNING medication_id
)
INSERT INTO medivice.medication_ingredients (medication_id, ingredient_id, amount, unit)
SELECT m.medication_id, i.ingredient_id, 200, 'mL'
  FROM new_medication m
 CROSS JOIN medivice.ingredients i
 WHERE i.ingr_code = '__UNIT_VOL__';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM medivice.v_overdose o
          JOIN medivice.users u ON u.user_id = o.user_id
          JOIN medivice.ingredients i ON i.ingredient_id = o.ingredient_id
         WHERE u.login_id = '__unit_check__'
           AND i.ingr_code = '__UNIT_VOL__'
    ) THEN
        RAISE EXCEPTION '농도 없는 mL가 mg 상한과 비교됨';
    END IF;
END $$;

ROLLBACK;
