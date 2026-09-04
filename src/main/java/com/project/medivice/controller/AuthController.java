package com.project.medivice.controller;

import com.project.medivice.dto.LoginRequest;
import com.project.medivice.dto.SignupRequest;
import com.project.medivice.dto.UserDto;
import com.project.medivice.dto.UserSummaryDto;
import com.project.medivice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC1·UC2. 응답의 UserDto.username(=loginId)을 프론트가 X-Medivice-User 헤더로 돌려보내면
 * 그 다음부터 모든 요청이 이 사용자로 처리된다(DemoUserResolver 참고).
 */
@Tag(name = "인증", description = "회원가입·로그인. 세션·토큰 없이 응답의 username을 이후 X-Medivice-User 헤더로 실어 보내는 방식(Sprint 1 축소 범위, 비밀번호 미검증)")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "회원가입", description = "이미 있는 아이디면 새로 만들지 않고 그 사용자를 그대로 반환한다. 비밀번호는 저장만 하고 검증하지 않는다.")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @Operation(summary = "로그인", description = "아이디 존재 여부만 확인한다(비밀번호 미검증). 없으면 404.")
    @PostMapping("/login")
    public UserDto login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(
            summary = "회원 목록 조회",
            description = "회원가입(POST /api/auth/signup)이 실제로 DB에 저장됐는지 Swagger에서 바로 확인하는 용도. "
                    + "password_hash 등 민감 정보는 내려주지 않는다.")
    @GetMapping("/users")
    public List<UserSummaryDto> users() {
        return authService.listUsers();
    }
}
