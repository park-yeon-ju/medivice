package com.project.medivice.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DepartmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DepartmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> findIdByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT department_id FROM medivice.departments WHERE name = :name";
        List<Integer> ids = jdbc.query(sql, new MapSqlParameterSource("name", name),
                (rs, n) -> rs.getInt("department_id"));
        return ids.stream().findFirst();
    }
}
