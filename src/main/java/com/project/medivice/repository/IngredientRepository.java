package com.project.medivice.repository;

import com.project.medivice.dto.IngredientSummaryDto;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngredientRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IngredientRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Optional<Long> findIdByName(String nameKo) {
        String sql = "SELECT ingredient_id FROM medivice.ingredients WHERE name_ko = :name LIMIT 1";
        List<Long> ids = jdbc.query(sql, new MapSqlParameterSource("name", nameKo),
                (rs, n) -> rs.getLong("ingredient_id"));
        return ids.stream().findFirst();
    }

    /**
     * 식약처 DUR 마스터에 있는 성분이면 그 id를 재사용하고(그래야 병용금기·중복 판정에 걸린다),
     * 없으면 사용자가 입력한 이름 그대로 새 성분을 만든다. 새로 만든 성분은 어떤 DUR 규칙에도
     * 걸리지 않으므로 v_uncovered_ingredients(UC31)에 잡혀 "판정하지 못함"으로 안내된다 —
     * 이것이 침묵하지 않고 정직하게 실패하는 설계다.
     */
    public Long findOrCreateByName(String nameKo) {
        return findIdByName(nameKo).orElseGet(() -> {
            String ingrCode = "USR-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
            String sql = """
                    INSERT INTO medivice.ingredients (ingr_code, name_ko)
                    VALUES (:code, :name)
                    RETURNING ingredient_id
                    """;
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("code", ingrCode)
                    .addValue("name", nameKo);
            return jdbc.queryForObject(sql, params, Long.class);
        });
    }

    /**
     * GET /api/ingredients 검색. query가 비어 있으면 이름순으로 상위 limit개만 돌려준다 —
     * 성분 마스터가 952종이라 전체를 그냥 내려주면 Swagger에서 시험 호출할 때마다 응답이 크다.
     */
    public List<IngredientSummaryDto> search(String query, int limit) {
        String sql = """
                SELECT ingredient_id, name_ko, name_en, ingr_code
                  FROM medivice.ingredients
                 WHERE (:query::text IS NULL OR name_ko ILIKE '%' || :query || '%'
                                             OR name_en ILIKE '%' || :query || '%')
                 ORDER BY name_ko
                 LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, n) -> new IngredientSummaryDto(
                rs.getLong("ingredient_id"), rs.getString("name_ko"), rs.getString("name_en"),
                rs.getString("ingr_code")));
    }

    /** POST /api/ingredients/check 1단계 — 입력한 이름들을 성분 마스터와 정확히 일치하는 것만 찾는다. */
    public List<IngredientSummaryDto> findByNames(List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT ingredient_id, name_ko, name_en, ingr_code
                  FROM medivice.ingredients
                 WHERE name_ko IN (:names)
                """;
        return jdbc.query(sql, new MapSqlParameterSource("names", names), (rs, n) -> new IngredientSummaryDto(
                rs.getLong("ingredient_id"), rs.getString("name_ko"), rs.getString("name_en"),
                rs.getString("ingr_code")));
    }

    /**
     * findByNames가 정확히 못 찾았을 때 쓰는 부분 일치 후보 조회. 예: 사용자가 "덱시부프로펜"이라고
     * 쳤는데 마스터 이름은 "덱시부프로펜 디.씨."인 경우 — GET /api/ingredients 검색으로는 나오는데
     * 충돌 확인에서는 정확히 일치하는 이름을 요구해 조용히 "찾지 못함"으로 빠지는 간극을 메운다.
     * 후보를 최대 5개까지 돌려주고, 호출부가 "정확히 하나뿐일 때만" 자동으로 채택한다 — 여러 개면
     * 어떤 걸 골라야 할지 모호하므로 추측하지 않는다(예: "부프로펜"은 이부프로펜과도 겹친다).
     */
    public List<IngredientSummaryDto> searchByPartialName(String name) {
        String sql = """
                SELECT ingredient_id, name_ko, name_en, ingr_code
                  FROM medivice.ingredients
                 WHERE name_ko ILIKE '%' || :name || '%' OR name_en ILIKE '%' || :name || '%'
                 ORDER BY length(name_ko)
                 LIMIT 5
                """;
        return jdbc.query(sql, new MapSqlParameterSource("name", name), (rs, n) -> new IngredientSummaryDto(
                rs.getLong("ingredient_id"), rs.getString("name_ko"), rs.getString("name_en"),
                rs.getString("ingr_code")));
    }

    /**
     * 이 성분 id들 중 병용금기·효능군중복·1일상한·단일금기(임부·연령 등) 어느 규칙에도 걸려 있지
     * 않은 것을 가려내기 위한 조회 — 마스터에 같은 이름이 두 번 등록된 경우(예: "나프록센" 252·905)
     * 중 어느 쪽이 실제로 DUR 규칙과 연결된 "진짜" 행인지 고를 때 쓴다(종류를 가리지 않고 아무
     * DUR 데이터나 있으면 그 행을 채택). 병용금기·효능군중복만 좁혀서 봐야 하는 pair-check의
     * "규칙 없음" 경고에는 findIdsWithPairOrEffectData를 대신 쓴다 — single_rules(임부·연령 등)만
     * 있는 행은 pair-check 입장에선 여전히 "고아"이기 때문이다.
     */
    public Set<Long> findIdsWithDurData(List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        String sql = """
                SELECT ingredient_id FROM medivice.ingredient_effect_groups WHERE ingredient_id IN (:ids)
                UNION
                SELECT ingredient_id FROM medivice.ingredient_daily_limits WHERE ingredient_id IN (:ids)
                UNION
                SELECT ingredient_id FROM medivice.dur_single_rules WHERE ingredient_id IN (:ids)
                UNION
                SELECT ingredient_a_id AS ingredient_id FROM medivice.dur_pair_rules WHERE ingredient_a_id IN (:ids)
                UNION
                SELECT ingredient_b_id AS ingredient_id FROM medivice.dur_pair_rules WHERE ingredient_b_id IN (:ids)
                """;
        List<Long> found = jdbc.query(sql, new MapSqlParameterSource("ids", ids),
                (rs, n) -> rs.getLong("ingredient_id"));
        return new LinkedHashSet<>(found);
    }

    /**
     * findIdsWithDurData와 달리 병용금기(dur_pair_rules)·효능군중복(ingredient_effect_groups)만
     * 본다. pair-check는 이 두 규칙만 판정에 쓰므로, "케토롤락"(952)처럼 단일금기(dur_single_rules)만
     * 있고 병용금기·효능군중복 연결은 하나도 없는 행을 "규칙이 있다"고 잘못 넘어가지 않기 위해
     * 별도로 좁혀서 조회한다.
     */
    public Set<Long> findIdsWithPairOrEffectData(List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        String sql = """
                SELECT ingredient_id FROM medivice.ingredient_effect_groups WHERE ingredient_id IN (:ids)
                UNION
                SELECT ingredient_a_id AS ingredient_id FROM medivice.dur_pair_rules WHERE ingredient_a_id IN (:ids)
                UNION
                SELECT ingredient_b_id AS ingredient_id FROM medivice.dur_pair_rules WHERE ingredient_b_id IN (:ids)
                """;
        List<Long> found = jdbc.query(sql, new MapSqlParameterSource("ids", ids),
                (rs, n) -> rs.getLong("ingredient_id"));
        return new LinkedHashSet<>(found);
    }

    public record DailyLimitRow(Long ingredientId, BigDecimal maxQty, String unit) {
    }

    /** 입력에 등장한 성분들 중 1일 상한이 정해진 것만 돌려준다(용량주의 판정용, v_overdose와 같은 테이블). */
    public List<DailyLimitRow> findDailyLimits(List<Long> ingredientIds) {
        if (ingredientIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT ingredient_id, max_qty, unit
                  FROM medivice.ingredient_daily_limits
                 WHERE ingredient_id IN (:ids)
                """;
        return jdbc.query(sql, new MapSqlParameterSource("ids", ingredientIds), (rs, n) -> new DailyLimitRow(
                rs.getLong("ingredient_id"), rs.getBigDecimal("max_qty"), rs.getString("unit")));
    }

    public record PairRuleRow(
            Integer durTypeId, String durTypeName, String severity, String prohibitContent,
            Long ingredientAId, Long ingredientBId) {
    }

    /**
     * 입력한 성분들 사이의 병용금기(dur_pair_rules)를 찾는다. 저장 규칙이 (a<b) 한 방향이라,
     * 두 id 모두 입력 집합 안에 있는 행만 골라내면 사용자가 어떤 순서로 입력했든 다 잡힌다.
     */
    public List<PairRuleRow> findPairRulesAmong(List<Long> ingredientIds) {
        if (ingredientIds.size() < 2) {
            return List.of();
        }
        String sql = """
                SELECT r.dur_type_id, t.name_ko AS dur_type_name, t.severity, r.prohibit_content,
                       r.ingredient_a_id, r.ingredient_b_id
                  FROM medivice.dur_pair_rules r
                  JOIN medivice.dur_types t ON t.dur_type_id = r.dur_type_id
                 WHERE r.ingredient_a_id IN (:ids) AND r.ingredient_b_id IN (:ids)
                """;
        return jdbc.query(sql, new MapSqlParameterSource("ids", ingredientIds), (rs, n) -> new PairRuleRow(
                rs.getInt("dur_type_id"), rs.getString("dur_type_name"), rs.getString("severity"),
                rs.getString("prohibit_content"), rs.getLong("ingredient_a_id"), rs.getLong("ingredient_b_id")));
    }

    public record EffectOverlapRow(Long ingredientAId, Long ingredientBId, String effectName) {
    }

    /** 입력한 성분들 중 같은 효능군(예: 해열진통소염제)에 속하는 서로 다른 성분 쌍을 찾는다. */
    public List<EffectOverlapRow> findEffectOverlapsAmong(List<Long> ingredientIds) {
        if (ingredientIds.size() < 2) {
            return List.of();
        }
        String sql = """
                SELECT g1.ingredient_id AS ingredient_a_id, g2.ingredient_id AS ingredient_b_id,
                       e.name AS effect_name
                  FROM medivice.ingredient_effect_groups g1
                  JOIN medivice.ingredient_effect_groups g2
                    ON g1.effect_group_id = g2.effect_group_id AND g1.ingredient_id < g2.ingredient_id
                  JOIN medivice.effect_groups e ON e.effect_group_id = g1.effect_group_id
                 WHERE g1.ingredient_id IN (:ids) AND g2.ingredient_id IN (:ids)
                """;
        return jdbc.query(sql, new MapSqlParameterSource("ids", ingredientIds), (rs, n) -> new EffectOverlapRow(
                rs.getLong("ingredient_a_id"), rs.getLong("ingredient_b_id"), rs.getString("effect_name")));
    }
}
