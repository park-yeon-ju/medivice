package com.project.medivice.service;

import com.project.medivice.dto.LoginRequest;
import com.project.medivice.dto.SignupRequest;
import com.project.medivice.dto.UserDto;
import com.project.medivice.dto.UserSummaryDto;
import com.project.medivice.exception.NotFoundException;
import com.project.medivice.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * UC1·UC2. Sprint 1은 인증을 구현하지 않기로 축소했으므로(스프린트 계획), 비밀번호는 실제로
 * 해싱·검증하지 않는다 — 여기서 하는 일은 "이 login_id로 이후 요청을 식별할 수 있게" 만드는
 * 것뿐이다(DemoUserResolver가 X-Medivice-User 헤더로 이 login_id를 돌려받아 사용자를 찾는다).
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserService userService;

    public AuthService(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    public UserDto signup(SignupRequest request) {
        String loginId = request.loginId().trim();
        // 이미 존재하는 아이디로 다시 가입을 시도해도 실패시키지 않고 그 사용자를 그대로 돌려준다 —
        // 비밀번호를 검증하지 않는 이상 "가입"과 "로그인"을 굳이 다른 결과로 구분할 이유가 없다.
        Long userId = userRepository.findIdByLoginId(loginId).orElseGet(() -> {
            String gender = mapGender(request.sex());
            LocalDate birthDate = parseBirthDate(request.birthDate());
            return userRepository.insert(loginId, "not-a-real-hash", gender, birthDate);
        });
        return userService.buildUser(userId);
    }

    public UserDto login(LoginRequest request) {
        String loginId = request.loginId().trim();
        Long userId = userRepository.findIdByLoginId(loginId)
                .orElseThrow(() -> new NotFoundException("존재하지 않는 아이디입니다: " + loginId));
        return userService.buildUser(userId);
    }

    /** 회원가입이 실제로 DB에 저장됐는지 Swagger에서 바로 확인할 수 있게 하는 목록 조회. */
    public List<UserSummaryDto> listUsers() {
        return userService.listSummaries();
    }

    private static String mapGender(String sex) {
        if ("남성".equals(sex)) {
            return "M";
        }
        if ("여성".equals(sex)) {
            return "F";
        }
        return null;
    }

    private static LocalDate parseBirthDate(String birthDate) {
        if (birthDate == null || birthDate.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(birthDate);
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDate.now();
        }
    }
}
