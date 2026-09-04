package com.project.medivice.config;

import com.project.medivice.service.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Medivice-User 헤더를 읽어 이번 요청의 {@link CurrentUserContext}에 채운다.
 * 헤더가 없으면 아무것도 하지 않고, DemoUserResolver가 기본 데모 사용자로 폴백한다 —
 * 기존에 이 헤더를 모르는 호출부(스크립트, curl 테스트 등)는 그대로 동작한다.
 *
 * HTTP 헤더 값은 ISO-8859-1만 허용해서, 프론트는 한글 아이디를 encodeURIComponent로
 * 퍼센트 인코딩해 보낸다(안 그러면 브라우저 fetch()가 "non ISO-8859-1 code point"로 그 자리에서
 * 막힌다). 여기서 짝을 맞춰 디코딩한다 — 순수 ASCII 아이디는 % 시퀀스가 없어 그대로 통과한다.
 */
@Component
public class CurrentUserFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Medivice-User";

    private final CurrentUserContext currentUserContext;

    public CurrentUserFilter(CurrentUserContext currentUserContext) {
        this.currentUserContext = currentUserContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String loginId = request.getHeader(HEADER_NAME);
        if (loginId != null && !loginId.isBlank()) {
            currentUserContext.setLoginId(decode(loginId.trim()));
        }
        chain.doFilter(request, response);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 인코딩되지 않은 값이 우연히 %처럼 보이는 문자를 포함한 경우 — 원본 그대로 쓴다.
            return value;
        }
    }
}
