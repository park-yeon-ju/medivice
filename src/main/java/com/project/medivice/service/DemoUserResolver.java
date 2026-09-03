package com.project.medivice.service;

import com.project.medivice.config.MediviceProperties;
import com.project.medivice.exception.NotFoundException;
import com.project.medivice.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Sprint 1 DoD: "데모는 고정 목 사용자로 진행"이지만, 세션·토큰 없이도 "누구로 요청을
 * 처리할지"는 알아야 회원가입한 아이디가 새로고침 후에도 그대로 유지된다. 그래서 이 리졸버는
 * X-Medivice-User 헤더(CurrentUserContext, CurrentUserFilter가 채움)가 있으면 그 사용자로,
 * 없으면(헤더를 모르는 옛 호출부) properties의 고정 데모 사용자로 폴백한다.
 * 모든 컨트롤러가 이 리졸버 하나만 거쳐 사용자를 얻으므로, 나중에 실제 인증을 붙일 때도
 * 교체 지점은 여기 하나로 좁혀져 있다.
 */
@Component
public class DemoUserResolver {

    private final UserRepository userRepository;
    private final MediviceProperties properties;
    private final CurrentUserContext currentUserContext;

    public DemoUserResolver(UserRepository userRepository, MediviceProperties properties,
            CurrentUserContext currentUserContext) {
        this.userRepository = userRepository;
        this.properties = properties;
        this.currentUserContext = currentUserContext;
    }

    public Long resolveUserId() {
        String loginId = currentUserContext.getLoginId();
        if (loginId == null || loginId.isBlank()) {
            String demoLoginId = properties.demoUserLoginId();
            return userRepository.findIdByLoginId(demoLoginId)
                    .orElseThrow(() -> new IllegalStateException(
                            "데모 사용자(login_id=" + demoLoginId + ")가 없습니다. "
                                    + "src/main/resources/db/05_backend_extensions.sql 을 먼저 실행하세요."));
        }
        return userRepository.findIdByLoginId(loginId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다: login_id=" + loginId));
    }
}
