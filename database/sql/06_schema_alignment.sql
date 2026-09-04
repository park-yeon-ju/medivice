/*
기존 medivice_db를 보존하면서 서비스 계약과 안전 고지에 필요한 컬럼만 추가한다.
실행: psql -d medivice_db -f sql/06_schema_alignment.sql
*/
BEGIN;

SET search_path TO medivice, public;

ALTER TABLE medivice.dur_single_rules
    ADD COLUMN IF NOT EXISTS rule_version VARCHAR(30) NOT NULL DEFAULT 'MFDS-DUR',
    ADD COLUMN IF NOT EXISTS source_ref TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15059486/openapi.do',
    ADD COLUMN IF NOT EXISTS checked_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE medivice.dur_pair_rules
    ADD COLUMN IF NOT EXISTS rule_version VARCHAR(30) NOT NULL DEFAULT 'MFDS-DUR',
    ADD COLUMN IF NOT EXISTS source_ref TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15056780/openapi.do',
    ADD COLUMN IF NOT EXISTS checked_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE medivice.effect_groups
    ADD COLUMN IF NOT EXISTS source_ref TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15059486/openapi.do',
    ADD COLUMN IF NOT EXISTS checked_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE medivice.ingredient_daily_limits
    ADD COLUMN IF NOT EXISTS rule_version VARCHAR(30) NOT NULL DEFAULT 'MFDS-DUR',
    ADD COLUMN IF NOT EXISTS source_ref TEXT NOT NULL DEFAULT 'https://www.data.go.kr/data/15059486/openapi.do',
    ADD COLUMN IF NOT EXISTS checked_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE medivice.medications
    ADD COLUMN IF NOT EXISTS as_needed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE medivice.safety_checks
    ADD COLUMN IF NOT EXISTS amount_missing_count SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE medivice.safety_checks
    DROP CONSTRAINT IF EXISTS chk_amount_missing,
    ADD CONSTRAINT chk_amount_missing CHECK (amount_missing_count >= 0);

ALTER TABLE medivice.safety_check_items
    ADD COLUMN IF NOT EXISTS unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reason_code VARCHAR(40),
    ADD COLUMN IF NOT EXISTS source_ref TEXT;

UPDATE medivice.safety_check_items
   SET reason_code = COALESCE(reason_code, 'LEGACY')
 WHERE reason_code IS NULL;

ALTER TABLE medivice.safety_check_items
    ALTER COLUMN reason_code SET NOT NULL;

ALTER TABLE medivice.ai_outputs DROP CONSTRAINT IF EXISTS chk_ai_status;
ALTER TABLE medivice.ai_outputs
    ADD CONSTRAINT chk_ai_status CHECK (status IN ('pending','processing','completed','failed'));

ALTER TABLE medivice.reports DROP CONSTRAINT IF EXISTS chk_report_status;
ALTER TABLE medivice.reports
    ADD CONSTRAINT chk_report_status CHECK (status IN ('pending','processing','completed','failed'));

COMMIT;
