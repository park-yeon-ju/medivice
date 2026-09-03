/*
작성목적 : 백엔드가 프론트엔드 계약(Medication.type/timing, Prescription.duration)을 채우기 위해
          DA 파이프라인의 01_schema_ddl.sql ~ 03_medilight_views.sql 위에 얹는 최소 추가분.
          DA 팀의 원본 3개 스크립트는 건드리지 않고, 이 파일만 그 뒤에 이어서 실행한다.
작성일 : 2026-09-02

실행 순서
  1) DA_데이터파이프라인/sql/01_schema_ddl.sql
  2) DA_데이터파이프라인/sql/02_seed_code.sql
  3) DA_데이터파이프라인/sql/03_medilight_views.sql
  4) (선택) DA_데이터파이프라인/sql/04_demo_scenario.sql — 판정 검증용 샘플 데이터
  5) 이 파일 (05_backend_extensions.sql)

무엇을, 왜 추가하는가
  ① medications.custom_type
     product_id 가 있으면 products.product_type(ETC/OTC/SUPPLEMENT)으로 유형을 알 수 있지만,
     UC13 수기 등록은 마스터에 없는 제품이라 product_id 가 NULL이다. 그 경우에도 화면이
     처방약/영양제/상비약 중 어떤 섹션에 넣을지 알아야 하므로, 사용자가 등록 화면에서 고른
     값을 여기에 남긴다. product_id가 있으면 이 컬럼은 쓰지 않는다(항상 NULL로 둔다).
  ② medications.timing
     "아침 · 저녁 · 필요 시" 같은 복용 시점은 판정에 쓰이지 않으므로 원 스키마에는 없었지만,
     프론트 화면(SCR-MAIN-003)에 표시할 값이 필요해 추가한다. 판정 로직은 이 컬럼을 보지 않는다.
  ③ prescriptions.duration_note
     "30일분" 같은 처방 일수 표기. OCR/수기 입력 텍스트를 그대로 보관하는 표시용 필드다.
  ④ 데모 사용자 시드
     04_demo_scenario.sql 은 판정 검증용 시나리오이고 백엔드 데모 계정과는 별개다.
     인증을 구현하지 않는 Sprint 1에서 API가 항상 참조할 고정 사용자를 멱등하게 만들어 둔다.
  ⑤ medications.dose_unit
     "정 · 캡슐 · mL" 같은 1회 투여 형태 표기. dose_per_intake(수량)와 짝을 이루는 표시용
     필드로, 성분 단위(mg/IU 등, medication_ingredients.unit)와는 다른 축이라 판정에 쓰이지
     않는다. UC13 등록 폼(MedicationCreateRequest.doseUnit)을 그대로 저장하기 위해 추가한다.
*/

SET search_path TO medivice, public;

ALTER TABLE medivice.medications
    ADD COLUMN IF NOT EXISTS custom_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS timing      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS dose_unit   VARCHAR(10) NOT NULL DEFAULT '정';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_med_custom_type') THEN
        ALTER TABLE medivice.medications
            ADD CONSTRAINT chk_med_custom_type
            CHECK (custom_type IS NULL OR custom_type IN ('PRESCRIPTION','OTC','SUPPLEMENT'));
    END IF;
END $$;

ALTER TABLE medivice.prescriptions
    ADD COLUMN IF NOT EXISTS duration_note VARCHAR(50);

-- 데모 사용자 (Sprint 1 고정 목 사용자 — UC1·2 축소)
INSERT INTO medivice.users (login_id, password_hash, gender, birth_date, lang)
VALUES ('demo_user', 'demo-not-a-real-hash', 'F', '1974-03-08', 'ko')
ON CONFLICT (login_id) DO NOTHING;

INSERT INTO medivice.user_profiles (user_id, height_cm, weight_kg, is_pregnant, renal_status)
SELECT user_id, 162.0, 58.0, FALSE, 'UNKNOWN'
  FROM medivice.users
 WHERE login_id = 'demo_user'
ON CONFLICT (user_id) DO NOTHING;
