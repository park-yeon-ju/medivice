package com.project.medivice.repository;

import java.util.List;
import java.util.Optional;
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
}
