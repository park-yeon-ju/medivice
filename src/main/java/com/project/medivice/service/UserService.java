package com.project.medivice.service;

import com.project.medivice.dto.UserDto;
import com.project.medivice.dto.UserSummaryDto;
import com.project.medivice.exception.NotFoundException;
import com.project.medivice.repository.UserRepository;
import com.project.medivice.repository.UserRepository.UserRow;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.springframework.stereotype.Service;

/** DashboardService·AuthService가 공유하는 UserDto 조립 로직. */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserDto buildUser(Long userId) {
        UserRow row = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다: id=" + userId));
        List<String> conditions = userRepository.findConditions(userId);
        List<String> allergies = userRepository.findAllergyIngredientNames(userId);
        Integer age = row.birthDate() != null ? Period.between(row.birthDate(), LocalDate.now()).getYears() : null;
        String sex = switch (row.gender() == null ? "" : row.gender()) {
            case "M" -> "남성";
            case "F" -> "여성";
            default -> null;
        };

        // 판정에 쓰이지 않는 개인정보(실명 등)는 스키마에 컬럼 자체가 없다(01_schema_ddl.sql 설계 원칙 ③).
        return new UserDto(row.id(), row.loginId(), row.loginId(), sex,
                row.birthDate() != null ? row.birthDate().toString() : null, age,
                conditions, allergies, row.heightCm(), row.weightKg(), null);
    }

    /** GET /api/auth/users — 회원가입이 DB에 실제로 저장됐는지 Swagger에서 눈으로 확인하는 용도. */
    public List<UserSummaryDto> listSummaries() {
        return userRepository.findAll().stream()
                .map(row -> {
                    String sex = switch (row.gender() == null ? "" : row.gender()) {
                        case "M" -> "남성";
                        case "F" -> "여성";
                        default -> null;
                    };
                    return new UserSummaryDto(row.id(), row.loginId(), sex,
                            row.birthDate() != null ? row.birthDate().toString() : null,
                            row.createdAt() != null ? row.createdAt().toString() : null);
                })
                .toList();
    }
}
