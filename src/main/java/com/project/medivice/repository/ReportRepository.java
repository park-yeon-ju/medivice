package com.project.medivice.repository;

import java.time.LocalDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * UC28·29 보고서. Sprint 1/2 범위에서는 AI 요약이 MockAiClient로 즉시 완료되므로 비동기 워커
 * 없이 요청 스레드 안에서 결과까지 만들어 completed 로 적재한다(ai_outputs와 달리 result_json은
 * 사람이 읽는 요약 텍스트만 담으면 되므로 여기서는 report 행 자체에 narrative를 넣지 않고
 * 서비스 계층에서 응답 DTO로만 조립한다 — 재계산 비용이 낮기 때문).
 */
@Repository
public class ReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long userId, LocalDate from, LocalDate to, String language, String status) {
        String sql = """
                INSERT INTO medivice.reports (user_id, period_start, period_end, language, status)
                VALUES (:userId, :from, :to, :language, :status)
                RETURNING report_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("language", language)
                .addValue("status", status);
        return jdbc.queryForObject(sql, params, Long.class);
    }
}
