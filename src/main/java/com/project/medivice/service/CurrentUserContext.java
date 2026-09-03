package com.project.medivice.service;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * 요청 하나 동안만 사는 로그인 아이디 보관함. Sprint 1은 세션·토큰 인증을 구현하지 않으므로
 * (스프린트 계획 UC1·2 축소), 프론트가 X-Medivice-User 헤더로 보낸 아이디를
 * {@link com.project.medivice.config.CurrentUserFilter}가 여기에 채워 넣고
 * {@link DemoUserResolver}가 읽어 어느 사용자로 요청을 처리할지 결정한다.
 */
@Component
@RequestScope
public class CurrentUserContext {

    private String loginId;

    public String getLoginId() {
        return loginId;
    }

    public void setLoginId(String loginId) {
        this.loginId = loginId;
    }
}
