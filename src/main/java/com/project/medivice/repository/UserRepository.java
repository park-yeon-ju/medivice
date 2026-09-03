package com.project.medivice.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public record UserRow(
            Long id, String loginId, String gender, LocalDate birthDate, String lang,
            Double heightCm, Double weightKg) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * UC1 회원가입. Sprint 1은 비밀번호를 실제로 해싱·검증하지 않는다(스프린트 계획 — 인증은
     * Sprint 3으로 축소). passwordHash는 자리표시자일 뿐이며 로그인 시 비교하지 않는다.
     */
    public Long insert(String loginId, String passwordHash, String gender, LocalDate birthDate) {
        String sql = """
                INSERT INTO medivice.users (login_id, password_hash, gender, birth_date)
                VALUES (:loginId, :passwordHash, :gender, :birthDate)
                RETURNING user_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("loginId", loginId)
                .addValue("passwordHash", passwordHash)
                .addValue("gender", gender)
                .addValue("birthDate", birthDate);
        return jdbc.queryForObject(sql, params, Long.class);
    }

    public Optional<Long> findIdByLoginId(String loginId) {
        String sql = "SELECT user_id FROM medivice.users WHERE login_id = :loginId";
        List<Long> ids = jdbc.query(sql, new MapSqlParameterSource("loginId", loginId),
                (rs, n) -> rs.getLong("user_id"));
        return ids.stream().findFirst();
    }

    public Optional<UserRow> findById(Long userId) {
        String sql = """
                SELECT u.user_id, u.login_id, u.gender, u.birth_date, u.lang,
                       p.height_cm, p.weight_kg
                  FROM medivice.users u
                  LEFT JOIN medivice.user_profiles p ON p.user_id = u.user_id
                 WHERE u.user_id = :userId
                """;
        List<UserRow> rows = jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> new UserRow(
                rs.getLong("user_id"),
                rs.getString("login_id"),
                rs.getString("gender"),
                rs.getObject("birth_date", LocalDate.class),
                rs.getString("lang"),
                toDouble(rs.getBigDecimal("height_cm")),
                toDouble(rs.getBigDecimal("weight_kg"))));
        return rows.stream().findFirst();
    }

    /** height_cm/weight_kg는 NUMERIC(5,1)이라 JDBC가 BigDecimal로 돌려준다 — Double로 좁혀 UserDto에 맞춘다. */
    private static Double toDouble(java.math.BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    public List<String> findConditions(Long userId) {
        String sql = "SELECT name FROM medivice.user_conditions WHERE user_id = :userId ORDER BY condition_id";
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> rs.getString("name"));
    }

    public List<String> findAllergyIngredientNames(Long userId) {
        String sql = """
                SELECT i.name_ko
                  FROM medivice.user_allergies a
                  JOIN medivice.ingredients i ON i.ingredient_id = a.ingredient_id
                 WHERE a.user_id = :userId
                 ORDER BY a.noted_at
                """;
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, n) -> rs.getString("name_ko"));
    }
}
