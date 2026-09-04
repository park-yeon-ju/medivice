package com.project.medivice.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MedicationRepository {

    public record MedicationHeaderRow(
            Long medicationId, String customName, String customType, String timing, String doseUnit,
            BigDecimal dosePerIntake, Integer timesPerDay, String registerReason,
            LocalDate startedAt, String source,
            Long productId, String productName, String productType,
            Long prescriptionId, String hospitalName, String durationNote,
            String departmentName, String reasonDetail) {
    }

    public record IngredientRow(
            Long medicationId, String nameKo, String nameEn, BigDecimal amount, String unit) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public MedicationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** DoD: "목록이 처방약(병원·진료과별) → 영양제·상비약 순으로 정렬된다". */
    public List<MedicationHeaderRow> findActiveByUser(Long userId) {
        String sql = """
                SELECT m.medication_id, m.custom_name, m.custom_type, m.timing, m.dose_unit,
                       m.dose_per_intake, m.times_per_day, m.register_reason, m.started_at, m.source,
                       p.product_id, p.name_ko AS product_name, p.product_type,
                       pr.prescription_id, pr.hospital_name, pr.duration_note,
                       d.name AS department_name, pr.reason_detail
                  FROM medivice.medications m
                  LEFT JOIN medivice.products p ON p.product_id = m.product_id
                  LEFT JOIN medivice.prescriptions pr ON pr.prescription_id = m.prescription_id
                  LEFT JOIN medivice.departments d ON d.department_id = pr.department_id
                 WHERE m.user_id = :userId AND m.ended_at IS NULL
                 ORDER BY (pr.prescription_id IS NULL) ASC,
                          pr.hospital_name NULLS LAST,
                          d.name NULLS LAST,
                          m.created_at ASC
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new MedicationHeaderRow(
                rs.getLong("medication_id"),
                rs.getString("custom_name"),
                rs.getString("custom_type"),
                rs.getString("timing"),
                rs.getString("dose_unit"),
                rs.getBigDecimal("dose_per_intake"),
                (Integer) rs.getObject("times_per_day"),
                rs.getString("register_reason"),
                rs.getObject("started_at", LocalDate.class),
                rs.getString("source"),
                (Long) rs.getObject("product_id"),
                rs.getString("product_name"),
                rs.getString("product_type"),
                (Long) rs.getObject("prescription_id"),
                rs.getString("hospital_name"),
                rs.getString("duration_note"),
                rs.getString("department_name"),
                rs.getString("reason_detail")));
    }

    /** 마스터 매칭분(product_ingredients)과 수기 등록분(medication_ingredients)을 합쳐 성분 상세를 만든다. */
    public List<IngredientRow> findIngredientRowsByUser(Long userId) {
        String sql = """
                SELECT m.medication_id, i.name_ko, i.name_en, pi.amount, pi.unit
                  FROM medivice.medications m
                  JOIN medivice.product_ingredients pi ON pi.product_id = m.product_id
                  JOIN medivice.ingredients i ON i.ingredient_id = pi.ingredient_id
                 WHERE m.user_id = :userId AND m.ended_at IS NULL
                UNION ALL
                SELECT m.medication_id, i.name_ko, i.name_en, mi.amount, mi.unit
                  FROM medivice.medications m
                  JOIN medivice.medication_ingredients mi ON mi.medication_id = m.medication_id
                  JOIN medivice.ingredients i ON i.ingredient_id = mi.ingredient_id
                 WHERE m.user_id = :userId AND m.ended_at IS NULL
                 ORDER BY medication_id
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new IngredientRow(
                rs.getLong("medication_id"),
                rs.getString("name_ko"),
                rs.getString("name_en"),
                rs.getBigDecimal("amount"),
                rs.getString("unit")));
    }

    public Long insertPrescription(Long userId, String hospitalName, Integer departmentId,
            String reasonDetail, String durationNote) {
        String sql = """
                INSERT INTO medivice.prescriptions
                    (user_id, hospital_name, department_id, reason_detail, issued_date, source, duration_note)
                VALUES (:userId, :hospital, :departmentId, :reasonDetail, CURRENT_DATE, 'MANUAL', :durationNote)
                RETURNING prescription_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("hospital", hospitalName)
                .addValue("departmentId", departmentId)
                .addValue("reasonDetail", reasonDetail)
                .addValue("durationNote", durationNote);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    public Long insertMedication(Long userId, Long prescriptionId, String customName, String customType,
            String timing, String doseUnit, BigDecimal dosePerIntake, Integer timesPerDay, String registerReason) {
        String sql = """
                INSERT INTO medivice.medications
                    (user_id, prescription_id, product_id, custom_name, custom_type, timing, dose_unit,
                     dose_per_intake, times_per_day, register_reason, source)
                VALUES (:userId, :prescriptionId, NULL, :customName, :customType, :timing, :doseUnit,
                        :dosePerIntake, :timesPerDay, :registerReason, 'MANUAL')
                RETURNING medication_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("prescriptionId", prescriptionId)
                .addValue("customName", customName)
                .addValue("customType", customType)
                .addValue("timing", timing)
                .addValue("doseUnit", doseUnit)
                .addValue("dosePerIntake", dosePerIntake)
                .addValue("timesPerDay", timesPerDay)
                .addValue("registerReason", registerReason);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    public void insertMedicationIngredient(Long medicationId, Long ingredientId, BigDecimal amount, String unit) {
        String sql = """
                INSERT INTO medivice.medication_ingredients (medication_id, ingredient_id, amount, unit)
                VALUES (:medicationId, :ingredientId, :amount, :unit)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("medicationId", medicationId)
                .addValue("ingredientId", ingredientId)
                .addValue("amount", amount)
                .addValue("unit", unit);
        jdbc.update(sql, params);
    }

    /** UC14: 물리적으로 지우지 않고 종료 처리해 이력을 보존한다. */
    public int softDelete(Long medicationId, Long userId) {
        String sql = """
                UPDATE medivice.medications
                   SET ended_at = CURRENT_DATE
                 WHERE medication_id = :medicationId AND user_id = :userId AND ended_at IS NULL
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("medicationId", medicationId)
                .addValue("userId", userId);
        return jdbc.update(sql, params);
    }

    /** 복용 항목 단건 조회 — 사용자 소유 및 활성 상태(ended_at IS NULL)를 확인한다. */
    public Optional<MedicationHeaderRow> findActiveById(Long medicationId, Long userId) {
        String sql = """
                SELECT m.medication_id, m.custom_name, m.custom_type, m.timing, m.dose_unit,
                       m.dose_per_intake, m.times_per_day, m.register_reason, m.started_at, m.source,
                       p.product_id, p.name_ko AS product_name, p.product_type,
                       pr.prescription_id, pr.hospital_name, pr.duration_note,
                       d.name AS department_name, pr.reason_detail
                  FROM medivice.medications m
                  LEFT JOIN medivice.products p ON p.product_id = m.product_id
                  LEFT JOIN medivice.prescriptions pr ON pr.prescription_id = m.prescription_id
                  LEFT JOIN medivice.departments d ON d.department_id = pr.department_id
                 WHERE m.medication_id = :medicationId AND m.user_id = :userId AND m.ended_at IS NULL
                """;
        List<MedicationHeaderRow> rows = jdbc.query(sql,
                new MapSqlParameterSource().addValue("medicationId", medicationId).addValue("userId", userId),
                (rs, n) -> new MedicationHeaderRow(
                        rs.getLong("medication_id"),
                        rs.getString("custom_name"),
                        rs.getString("custom_type"),
                        rs.getString("timing"),
                        rs.getString("dose_unit"),
                        rs.getBigDecimal("dose_per_intake"),
                        (Integer) rs.getObject("times_per_day"),
                        rs.getString("register_reason"),
                        rs.getObject("started_at", LocalDate.class),
                        rs.getString("source"),
                        (Long) rs.getObject("product_id"),
                        rs.getString("product_name"),
                        rs.getString("product_type"),
                        (Long) rs.getObject("prescription_id"),
                        rs.getString("hospital_name"),
                        rs.getString("duration_note"),
                        rs.getString("department_name"),
                        rs.getString("reason_detail")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** UC14 보완: 복용 항목의 사용자 입력값(용량·시점·이름 등)을 수정한다. */
    public void updateMedication(Long medicationId, Long userId, String customName, String customType,
            String timing, String doseUnit, BigDecimal dosePerIntake, Integer timesPerDay, String registerReason) {
        String sql = """
                UPDATE medivice.medications
                   SET custom_name = :customName,
                       custom_type = :customType,
                       timing = :timing,
                       dose_unit = :doseUnit,
                       dose_per_intake = :dosePerIntake,
                       times_per_day = :timesPerDay,
                       register_reason = :registerReason
                 WHERE medication_id = :medicationId AND user_id = :userId AND ended_at IS NULL
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("medicationId", medicationId)
                .addValue("userId", userId)
                .addValue("customName", customName)
                .addValue("customType", customType)
                .addValue("timing", timing)
                .addValue("doseUnit", doseUnit)
                .addValue("dosePerIntake", dosePerIntake)
                .addValue("timesPerDay", timesPerDay)
                .addValue("registerReason", registerReason);
        jdbc.update(sql, params);
    }

    /** UC14 보완: 처방전 정보(병원·진료과·사유·기간)를 수정한다. */
    public void updatePrescription(Long prescriptionId, String hospitalName, Integer departmentId,
            String reasonDetail, String durationNote) {
        String sql = """
                UPDATE medivice.prescriptions
                   SET hospital_name = :hospital,
                       department_id = :departmentId,
                       reason_detail = :reasonDetail,
                       duration_note = :durationNote
                 WHERE prescription_id = :prescriptionId
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("prescriptionId", prescriptionId)
                .addValue("hospital", hospitalName)
                .addValue("departmentId", departmentId)
                .addValue("reasonDetail", reasonDetail)
                .addValue("durationNote", durationNote);
        jdbc.update(sql, params);
    }

    /** 기존 일반약/영양제에서 처방약으로 변경되어 처방전 ID를 새로 부여할 때 연결한다. */
    public void attachPrescription(Long medicationId, Long prescriptionId) {
        String sql = """
                UPDATE medivice.medications
                   SET prescription_id = :prescriptionId
                 WHERE medication_id = :medicationId
                """;
        jdbc.update(sql, new MapSqlParameterSource().addValue("medicationId", medicationId).addValue("prescriptionId", prescriptionId));
    }

    /** 수정 시 성분 목록을 교체하기 위해 기존 성분 연결을 지운다. */
    public void deleteMedicationIngredients(Long medicationId) {
        String sql = """
                DELETE FROM medivice.medication_ingredients
                 WHERE medication_id = :medicationId
                """;
        jdbc.update(sql, new MapSqlParameterSource("medicationId", medicationId));
    }

    public record MedicationNameRow(Long id, String name) {
    }

    public List<MedicationNameRow> findActiveIdsAndNames(Long userId) {
        String sql = """
                SELECT m.medication_id, COALESCE(p.name_ko, m.custom_name) AS name
                  FROM medivice.medications m
                  LEFT JOIN medivice.products p ON p.product_id = m.product_id
                 WHERE m.user_id = :userId AND m.ended_at IS NULL
                 ORDER BY m.created_at
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId),
                (rs, n) -> new MedicationNameRow(rs.getLong("medication_id"), rs.getString("name")));
    }
}
