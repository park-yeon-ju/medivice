package com.project.medivice.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * medivice.ai_outputs(target_type/target_id/result_json)에 약 설명(§29)을 저장·조회한다.
 * 등록 때 한 번만 생성해서 여기 캐시해 두고, 목록 조회 때마다 AI를 다시 부르지 않는다
 * (OcrService의 이미지 인식과 달리 이건 짧은 텍스트 생성이라 등록 요청 스레드 안에서
 * 동기로 끝내도 될 만큼 빠르다 — ReportService.summarizeReport와 같은 판단).
 */
@Repository
public class AiOutputRepository {

    private static final String TARGET_TYPE = "MEDICATION";

    private final NamedParameterJdbcTemplate jdbc;

    public AiOutputRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveMedicationExplanation(Long medicationId, String prompt, String explanation) {
        String sql = """
                INSERT INTO medivice.ai_outputs (target_type, target_id, prompt, result_json, status)
                VALUES (:targetType, :medicationId, :prompt, to_jsonb(:explanation::text), 'completed')
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", TARGET_TYPE)
                .addValue("medicationId", medicationId)
                .addValue("prompt", prompt)
                .addValue("explanation", explanation);
        jdbc.update(sql, params);
    }

    /** 등록 시점에 생성 자체가 실패했을 때(§29) 남기는 기록 — 화면에는 그냥 설명 없음으로 보인다. */
    public void saveMedicationExplanationFailure(Long medicationId, String prompt, String error) {
        String sql = """
                INSERT INTO medivice.ai_outputs (target_type, target_id, prompt, result_json, status)
                VALUES (:targetType, :medicationId, :prompt, to_jsonb(:error::text), 'failed')
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", TARGET_TYPE)
                .addValue("medicationId", medicationId)
                .addValue("prompt", prompt)
                .addValue("error", error);
        jdbc.update(sql, params);
    }

    /** 목록 조회용 — 여러 medication_id의 설명을 한 번에 읽는다. 없는 id는 맵에 안 나온다. */
    public Map<Long, String> findExplanations(Collection<Long> medicationIds) {
        if (medicationIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT DISTINCT ON (target_id) target_id, result_json #>> '{}' AS explanation
                  FROM medivice.ai_outputs
                 WHERE target_type = :targetType AND target_id IN (:ids) AND status = 'completed'
                 ORDER BY target_id, created_at DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", TARGET_TYPE)
                .addValue("ids", List.copyOf(medicationIds));
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            result.put(rs.getLong("target_id"), rs.getString("explanation"));
        });
        return result;
    }
}
