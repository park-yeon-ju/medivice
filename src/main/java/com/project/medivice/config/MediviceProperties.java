package com.project.medivice.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sprint 3가 "미리 비워둘 자리"로 지정한 확장 지점(ai.provider, rule-version)을
 * 코드가 아니라 설정값으로 뺀다. 구현체를 바꿀 때 이 클래스는 수정하지 않는다.
 */
@ConfigurationProperties(prefix = "medivice")
public record MediviceProperties(
        String demoUserLoginId,
        String ruleVersion,
        Ai ai,
        Cors cors) {

    public record Ai(String provider) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
