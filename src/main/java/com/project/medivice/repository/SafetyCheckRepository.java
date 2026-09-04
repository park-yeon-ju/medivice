package com.project.medivice.repository;

import java.math.BigDecimal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 등록·삭제마다 판정 스냅샷을 safety_checks / safety_check_items 에 남긴다.
 * DoD: "판정 사유에 계산식과 기준 출처·확인일이 함께 표시된다" — 이 스냅샷이 그 확인일의 근거가 된다.
 */
@Repository
public class SafetyCheckRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SafetyCheckRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertCheck(Long userId, String level, String triggerType, int uncoveredCount) {
        String sql = """
                INSERT INTO medivice.safety_checks (user_id, level, trigger_type, uncovered_count)
                VALUES (:userId, :level, :triggerType, :uncoveredCount)
                RETURNING check_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("level", level)
                .addValue("triggerType", triggerType)
                .addValue("uncoveredCount", uncoveredCount);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    /**
     * dur_type_id 가 없는(NO_DUR_DATA/효능군중복 등 단일 원인이 아닌) 항목은 null로 둔다.
     * reasonCode는 06_schema_alignment.sql이 NOT NULL로 추가한 컬럼 — MedilightService의
     * IngredientAnalysisDto.reasonCode와 같은 어휘(OVER_LIMIT/DUPLICATE/NEAR_LIMIT 등)를 쓴다.
     */
    public void insertItem(Long checkId, Integer durTypeId, Long ingredientId,
            Long medicationAId, Long medicationBId, BigDecimal totalAmount, BigDecimal threshold, String level,
            String reasonCode) {
        String sql = """
                INSERT INTO medivice.safety_check_items
                    (check_id, dur_type_id, ingredient_id, medication_a_id, medication_b_id,
                     total_amount, threshold, level, reason_code)
                VALUES (:checkId, :durTypeId, :ingredientId, :medicationAId, :medicationBId,
                        :totalAmount, :threshold, :level, :reasonCode)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("checkId", checkId)
                .addValue("durTypeId", durTypeId)
                .addValue("ingredientId", ingredientId)
                .addValue("medicationAId", medicationAId)
                .addValue("medicationBId", medicationBId)
                .addValue("totalAmount", totalAmount)
                .addValue("threshold", threshold)
                .addValue("level", level)
                .addValue("reasonCode", reasonCode);
        jdbc.update(sql, params);
    }

    public Integer findNoDurDataTypeId() {
        return findDurTypeIdByCode("NO_DUR_DATA");
    }

    /** v_overdose(용량주의·중복)는 하나의 dur_single_rules 행에서 나온 게 아니므로 코드로 직접 찾는다. */
    public Integer findDurTypeIdByCode(String code) {
        String sql = "SELECT dur_type_id FROM medivice.dur_types WHERE code = :code";
        return jdbc.queryForObject(sql, new MapSqlParameterSource("code", code), Integer.class);
    }
}
