package com.project.medivice.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 03_medilight_views.sql 이 만든 판정 뷰(v_active_ingredients 등)를 그대로 읽는다.
 * 색과 판정 사유는 이 뷰들이 결정하고, 여기서는 재계산하지 않는다 — "규칙 엔진은 DB 뷰"라는
 * 전제를 어기지 않기 위해서다.
 */
@Repository
public class MedilightViewRepository {

    public record ActiveIngredientRow(
            Long medicationId, Long ingredientId, String nameKo, String nameEn,
            String productName, BigDecimal dailyAmount, String unit,
            BigDecimal dosePerIntake, Integer timesPerDay) {
    }

    public record OverdoseRow(
            Long ingredientId, Integer medCount, BigDecimal totalDaily, BigDecimal maxQty, String unit, String level) {
    }

    public record PairConflictRow(
            Integer durTypeId, String durTypeName, String level, String prohibitContent,
            Long medicationAId, String medicationAName, Long medicationBId, String medicationBName,
            String ingredientAName, String ingredientBName) {
    }

    public record SingleConflictRow(
            Long medicationId, Long ingredientId, String ingredientName,
            Integer durTypeId, String durTypeName, String level, String prohibitContent) {
    }

    public record EffectDupRow(
            String effectName, String level,
            Long medicationAId, String medicationAName, Long medicationBId, String medicationBName) {
    }

    public record UncoveredRow(Long ingredientId, String nameKo, String nameEn, String ingrCode) {
    }

    public record OverallRow(String level, int uncoveredCount) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public MedilightViewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ActiveIngredientRow> findActiveIngredients(Long userId) {
        String sql = """
                SELECT va.medication_id, va.ingredient_id, i.name_ko, i.name_en,
                       COALESCE(p.name_ko, m.custom_name) AS product_name,
                       va.daily_amount, va.unit, m.dose_per_intake, m.times_per_day
                  FROM medivice.v_active_ingredients va
                  JOIN medivice.ingredients i ON i.ingredient_id = va.ingredient_id
                  JOIN medivice.medications m ON m.medication_id = va.medication_id
                  LEFT JOIN medivice.products p ON p.product_id = m.product_id
                 WHERE va.user_id = :userId
                 ORDER BY i.name_ko
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new ActiveIngredientRow(
                rs.getLong("medication_id"),
                rs.getLong("ingredient_id"),
                rs.getString("name_ko"),
                rs.getString("name_en"),
                rs.getString("product_name"),
                rs.getBigDecimal("daily_amount"),
                rs.getString("unit"),
                rs.getBigDecimal("dose_per_intake"),
                (Integer) rs.getObject("times_per_day")));
    }

    public List<OverdoseRow> findOverdose(Long userId) {
        String sql = """
                SELECT ingredient_id, med_count, total_daily, max_qty, unit, level
                  FROM medivice.v_overdose
                 WHERE user_id = :userId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new OverdoseRow(
                rs.getLong("ingredient_id"),
                rs.getInt("med_count"),
                rs.getBigDecimal("total_daily"),
                rs.getBigDecimal("max_qty"),
                rs.getString("unit"),
                rs.getString("level")));
    }

    /**
     * v_pair_conflict에 ingredient_a_id/ingredient_b_id가 이미 있는데, 예전엔 여기서 안 가져오고
     * 제품명(medication_a/b)만 채웠다 — 그래서 화면에 "메토트렉세이트정[2.5mg/1정] · 1"처럼
     * 실제로 충돌하는 두 "물질"이 아니라 제품명(그것도 "1" 같은 테스트 이름)이 보였다. 실제로
     * 병용금기가 걸리는 대상은 성분이므로, 성분명도 같이 가져온다(§39).
     */
    public List<PairConflictRow> findPairConflicts(Long userId) {
        String sql = """
                SELECT c.dur_type_id, t.name_ko AS dur_type_name, c.level, c.prohibit_content,
                       c.medication_a_id, COALESCE(pa.name_ko, ma.custom_name) AS medication_a_name,
                       c.medication_b_id, COALESCE(pb.name_ko, mb.custom_name) AS medication_b_name,
                       ia.name_ko AS ingredient_a_name, ib.name_ko AS ingredient_b_name
                  FROM medivice.v_pair_conflict c
                  JOIN medivice.dur_types t ON t.dur_type_id = c.dur_type_id
                  JOIN medivice.medications ma ON ma.medication_id = c.medication_a_id
                  JOIN medivice.medications mb ON mb.medication_id = c.medication_b_id
                  LEFT JOIN medivice.products pa ON pa.product_id = ma.product_id
                  LEFT JOIN medivice.products pb ON pb.product_id = mb.product_id
                  JOIN medivice.ingredients ia ON ia.ingredient_id = CASE
                       -- v_pair_conflict의 ingredient_a/b는 규칙 저장 순서(작은 ID 우선)라
                       -- medication_a/b 순서와 다를 수 있다. 화면의 "약 A · 성분 A"가 실제
                       -- 조합이 되도록 각 복용 항목이 보유한 성분을 기준으로 다시 짝지어 준다.
                       WHEN EXISTS (
                           SELECT 1 FROM medivice.v_active_ingredients mia
                            WHERE mia.medication_id = c.medication_a_id
                              AND mia.ingredient_id = c.ingredient_a_id
                       ) THEN c.ingredient_a_id
                       ELSE c.ingredient_b_id
                  END
                  JOIN medivice.ingredients ib ON ib.ingredient_id = CASE
                       WHEN EXISTS (
                           SELECT 1 FROM medivice.v_active_ingredients mib
                            WHERE mib.medication_id = c.medication_b_id
                              AND mib.ingredient_id = c.ingredient_a_id
                       ) THEN c.ingredient_a_id
                       ELSE c.ingredient_b_id
                  END
                 WHERE c.user_id = :userId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new PairConflictRow(
                (Integer) rs.getObject("dur_type_id"),
                rs.getString("dur_type_name"),
                rs.getString("level"),
                rs.getString("prohibit_content"),
                rs.getLong("medication_a_id"),
                rs.getString("medication_a_name"),
                rs.getLong("medication_b_id"),
                rs.getString("medication_b_name"),
                rs.getString("ingredient_a_name"),
                rs.getString("ingredient_b_name")));
    }

    public List<SingleConflictRow> findSingleConflicts(Long userId) {
        String sql = """
                SELECT sc.medication_id, sc.ingredient_id, i.name_ko AS ingredient_name,
                       sc.dur_type_id, t.name_ko AS dur_type_name,
                       sc.level, sc.prohibit_content
                  FROM medivice.v_single_conflict sc
                  JOIN medivice.ingredients i ON i.ingredient_id = sc.ingredient_id
                  JOIN medivice.dur_types t ON t.dur_type_id = sc.dur_type_id
                 WHERE sc.user_id = :userId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new SingleConflictRow(
                rs.getLong("medication_id"),
                rs.getLong("ingredient_id"),
                rs.getString("ingredient_name"),
                (Integer) rs.getObject("dur_type_id"),
                rs.getString("dur_type_name"),
                rs.getString("level"),
                rs.getString("prohibit_content")));
    }

    public List<EffectDupRow> findEffectDup(Long userId) {
        String sql = """
                SELECT e.effect_name, e.level,
                       e.medication_a_id, COALESCE(pa.name_ko, ma.custom_name) AS medication_a_name,
                       e.medication_b_id, COALESCE(pb.name_ko, mb.custom_name) AS medication_b_name
                  FROM medivice.v_effect_dup e
                  JOIN medivice.medications ma ON ma.medication_id = e.medication_a_id
                  JOIN medivice.medications mb ON mb.medication_id = e.medication_b_id
                  LEFT JOIN medivice.products pa ON pa.product_id = ma.product_id
                  LEFT JOIN medivice.products pb ON pb.product_id = mb.product_id
                 WHERE e.user_id = :userId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new EffectDupRow(
                rs.getString("effect_name"),
                rs.getString("level"),
                rs.getLong("medication_a_id"),
                rs.getString("medication_a_name"),
                rs.getLong("medication_b_id"),
                rs.getString("medication_b_name")));
    }

    public List<UncoveredRow> findUncoveredIngredients(Long userId) {
        String sql = """
                SELECT DISTINCT ingredient_id, name_ko, name_en, ingr_code
                  FROM medivice.v_uncovered_ingredients
                 WHERE user_id = :userId
                 ORDER BY name_ko
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new UncoveredRow(
                rs.getLong("ingredient_id"),
                rs.getString("name_ko"),
                rs.getString("name_en"),
                rs.getString("ingr_code")));
    }

    public OverallRow findOverall(Long userId) {
        String sql = "SELECT medilight_level, uncovered_count FROM medivice.v_medilight WHERE user_id = :userId";
        List<OverallRow> rows = jdbc.query(sql, new MapSqlParameterSource("userId", userId),
                (rs, n) -> new OverallRow(rs.getString("medilight_level"), rs.getInt("uncovered_count")));
        // v_medilight는 users 기준 LEFT JOIN이라 사용자가 존재하면 항상 한 행이 나온다.
        return rows.stream().findFirst().orElse(new OverallRow("GREEN", 0));
    }

    public Optional<String> findNoticeMessage(Long userId) {
        String sql = "SELECT notice_message FROM medivice.v_safety_notice WHERE user_id = :userId";
        List<String> rows = jdbc.query(sql, new MapSqlParameterSource("userId", userId),
                (rs, n) -> rs.getString("notice_message"));
        // notice_message 자체가 NULL인 행(판정 불가 성분 0개)이 정상 케이스라, Stream#findFirst()로
        // 그 null을 Optional에 담으려 하면 NPE가 난다 — 리스트를 직접 인덱싱해 우회한다.
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public Optional<LocalDateTime> findLatestCheckedAt(Long userId) {
        String sql = """
                SELECT checked_at FROM medivice.safety_checks
                 WHERE user_id = :userId
                 ORDER BY checked_at DESC
                 LIMIT 1
                """;
        // checked_at은 TIMESTAMPTZ라 드라이버가 LocalDateTime으로 직접 변환해주지 않는다
        // (시간대 정보가 없는 타입이라 거부한다) — OffsetDateTime으로 받은 뒤 좁힌다.
        List<LocalDateTime> rows = jdbc.query(sql, new MapSqlParameterSource("userId", userId),
                (rs, n) -> {
                    java.time.OffsetDateTime checkedAt = rs.getObject("checked_at", java.time.OffsetDateTime.class);
                    return checkedAt != null ? checkedAt.toLocalDateTime() : null;
                });
        return rows.stream().findFirst();
    }
}
