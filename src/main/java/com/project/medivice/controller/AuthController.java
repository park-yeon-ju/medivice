package com.project.medivice.controller;

import com.project.medivice.dto.LoginRequest;
import com.project.medivice.dto.SignupRequest;
import com.project.medivice.dto.UserDto;
import com.project.medivice.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC1·UC2. 응답의 UserDto.username(=loginId)을 프론트가 X-Medivice-User 헤더로 돌려보내면
 * 그 다음부터 모든 요청이 이 사용자로 처리된다(DemoUserResolver 참고).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/login")
    public UserDto login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
