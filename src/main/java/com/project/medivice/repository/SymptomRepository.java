package com.project.medivice.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Sprint 2-C(증상 기록, UC20~22)는 외부 의존이 없는 순수 CRUD라 스프린트 계획이 "여유가
 * 있으면 실제 구현으로 승격"을 권장한 그룹이다. 테이블이 이미 있으므로 그대로 구현했다.
 */
@Repository
public class SymptomRepository {

    public record LogRow(Long logId, LocalDate occurredDate, LocalDateTime writtenAt, String note) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SymptomRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LogRow> findRecentByUser(Long userId, int limit) {
        String sql = """
                SELECT log_id, occurred_date, written_at, note
                  FROM medivice.side_effect_logs
                 WHERE user_id = :userId
                 ORDER BY occurred_date DESC, written_at DESC
                 LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, n) -> new LogRow(
                rs.getLong("log_id"),
                rs.getObject("occurred_date", LocalDate.class),
                toLocalDateTime(rs, "written_at"),
                rs.getString("note")));
    }

    /** UC29 보고서 기간 필터. */
    public List<LogRow> findByUserAndRange(Long userId, LocalDate from, LocalDate to) {
        String sql = """
                SELECT log_id, occurred_date, written_at, note
                  FROM medivice.side_effect_logs
                 WHERE user_id = :userId AND occurred_date BETWEEN :from AND :to
                 ORDER BY occurred_date DESC, written_at DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("from", from).addValue("to", to);
        return jdbc.query(sql, params, (rs, n) -> new LogRow(
                rs.getLong("log_id"),
                rs.getObject("occurred_date", LocalDate.class),
                toLocalDateTime(rs, "written_at"),
                rs.getString("note")));
    }

    /** written_at은 TIMESTAMPTZ라 드라이버가 LocalDateTime으로 직접 변환해주지 않는다. */
    private static LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toLocalDateTime() : null;
    }

    public List<String> findSymptomNames(Long logId) {
        String sql = """
                SELECT s.name
                  FROM medivice.side_effect_symptoms x
                  JOIN medivice.symptoms s ON s.symptom_id = x.symptom_id
                 WHERE x.log_id = :logId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("logId", logId), (rs, n) -> rs.getString("name"));
    }

    public record SnapshotRow(Long medicationId, String productName) {
    }

    public List<SnapshotRow> findSnapshot(Long logId) {
        String sql = """
                SELECT medication_id, product_name
                  FROM medivice.side_effect_snapshots
                 WHERE log_id = :logId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("logId", logId),
                (rs, n) -> new SnapshotRow((Long) rs.getObject("medication_id"), rs.getString("product_name")));
    }

    public Long insertLog(Long userId, LocalDate occurredDate, String note) {
        String sql = """
                INSERT INTO medivice.side_effect_logs (user_id, occurred_date, note)
                VALUES (:userId, :occurredDate, :note)
                RETURNING log_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("occurredDate", occurredDate).addValue("note", note);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    private Optional<Integer> findSymptomId(String name) {
        String sql = "SELECT symptom_id FROM medivice.symptoms WHERE name = :name LIMIT 1";
        return jdbc.query(sql, new MapSqlParameterSource("name", name), (rs, n) -> rs.getInt("symptom_id"))
                .stream().findFirst();
    }

    /** 마스터에 없는 자유 입력 증상은 '기타' 분류로 새로 만든다. */
    public Integer findOrCreateSymptomId(String name) {
        return findSymptomId(name).orElseGet(() -> {
            String insert = """
                    INSERT INTO medivice.symptoms (category, name) VALUES ('기타', :name)
                    ON CONFLICT (category, name) DO NOTHING
                    """;
            jdbc.update(insert, new MapSqlParameterSource("name", name));
            return findSymptomId(name).orElseThrow();
        });
    }

    public void insertLogSymptom(Long logId, Integer symptomId) {
        String sql = "INSERT INTO medivice.side_effect_symptoms (log_id, symptom_id) VALUES (:logId, :symptomId)";
        jdbc.update(sql, new MapSqlParameterSource().addValue("logId", logId).addValue("symptomId", symptomId));
    }

    public void insertSnapshot(Long logId, Long medicationId, String productName) {
        String sql = """
                INSERT INTO medivice.side_effect_snapshots (log_id, medication_id, product_name)
                VALUES (:logId, :medicationId, :productName)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("logId", logId).addValue("medicationId", medicationId).addValue("productName", productName);
        jdbc.update(sql, params);
    }

    /** 로그를 지우면 ON DELETE CASCADE 로 side_effect_symptoms/snapshots 도 함께 지워진다. */
    public int deleteLog(Long logId, Long userId) {
        String sql = "DELETE FROM medivice.side_effect_logs WHERE log_id = :logId AND user_id = :userId";
        return jdbc.update(sql, new MapSqlParameterSource().addValue("logId", logId).addValue("userId", userId));
    }
}
