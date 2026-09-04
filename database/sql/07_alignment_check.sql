/* 수정된 함량 누락 경고와 API 상태 매핑의 최소 회귀 검사. 모든 테스트 데이터는 롤백된다. */
BEGIN;
SET search_path TO medivice, public;

DO $$
DECLARE
    uid BIGINT;
    iid BIGINT;
    pid BIGINT;
    api_status VARCHAR(4);
    missing_count SMALLINT;
    notice TEXT;
BEGIN
    INSERT INTO users(login_id, password_hash, gender, birth_date)
    VALUES ('__alignment_check__', 'test-only', 'M', DATE '1990-01-01')
    RETURNING user_id INTO uid;

    INSERT INTO ingredients(ingr_code, name_ko)
    VALUES ('__ALIGNMENT_INGR__', '함량누락검사성분')
    RETURNING ingredient_id INTO iid;

    INSERT INTO products(item_seq, name_ko, product_type)
    VALUES ('__ALIGN_PRODUCT__', '함량누락검사약', 'OTC')
    RETURNING product_id INTO pid;

    INSERT INTO product_ingredients(product_id, ingredient_id, amount, unit)
    VALUES (pid, iid, NULL, NULL);

    INSERT INTO medications(user_id, product_id, dose_per_intake, times_per_day, as_needed, source)
    VALUES (uid, pid, 1, 3, TRUE, 'MANUAL');

    SELECT status, amount_missing_count, notice_message
      INTO api_status, missing_count, notice
      FROM v_medilight_api
     WHERE user_id = uid;

    IF api_status <> 'OK' OR missing_count <> 1 OR position('함량 정보' IN notice) = 0 THEN
        RAISE EXCEPTION 'alignment check failed: status=%, missing=%, notice=%',
                        api_status, missing_count, notice;
    END IF;
END $$;

ROLLBACK;
