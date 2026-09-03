package com.project.medivice.controller;

import com.project.medivice.dto.MedilightDto;
import com.project.medivice.dto.MedilightPairCheckResponse;
import com.project.medivice.service.DemoUserResolver;
import com.project.medivice.service.IngredientCheckService;
import com.project.medivice.service.MedilightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 계약: GET /api/medilight → 200. */
@Tag(name = "메디라이트 판정", description = "현재 등록된 사용자의 복용 목록에 대한 규칙 엔진(DB 뷰) 판정 결과 조회 + 성분 두 개 즉석 확인")
@RestController
@RequestMapping("/api")
public class MedilightController {

    private final MedilightService medilightService;
    private final DemoUserResolver demoUserResolver;
    private final IngredientCheckService ingredientCheckService;

    public MedilightController(MedilightService medilightService, DemoUserResolver demoUserResolver,
            IngredientCheckService ingredientCheckService) {
        this.medilightService = medilightService;
        this.demoUserResolver = demoUserResolver;
        this.ingredientCheckService = ingredientCheckService;
    }

    @Operation(summary = "메디라이트 판정 조회", description = "성분별 하루 총량, 병용금기·효능군중복, 판정 근거 없는 성분까지 포함한 현재 상태(OK/WARN/CRIT)를 반환한다. 색은 DB 뷰가 결정하며 여기서 재계산하지 않는다.")
    @GetMapping("/medilight")
    public MedilightDto medilight() {
        return medilightService.build(demoUserResolver.resolveUserId());
    }

    @Operation(
            summary = "성분 두 개로 메디라이트 색 미리보기",
            description = """
                    등록된 사용자 없이, 성분 이름 두 칸만 입력해서 메디라이트가 어떤 색이 될지 바로 확인한다.
                    - **같은 성분 이름을 두 칸에 똑같이 입력** → 노랑(WARN, 중복/과복용 가능성)
                    - **병용금기(RED)로 등록된 서로 다른 두 성분** → 빨강(CRIT)
                    - **효능군중복 등(YELLOW)** → 노랑(WARN)
                    - 아무 규칙에도 안 걸리면 초록(OK)

                    빨강 테스트 예시: ingredientA=심바스타틴, ingredientB=이트라코나졸
                    (병용금기, 사유: 횡문근융해증 — DA팀 데모 시나리오와 동일한 실제 DUR 데이터)
                    노랑(중복) 테스트 예시: ingredientA=이트라코나졸, ingredientB=이트라코나졸""")
    @GetMapping("/medilight/pair-check")
    public MedilightPairCheckResponse pairCheck(
            @Parameter(description = "성분 A (DUR 마스터에 등록된 정확한 이름)", example = "심바스타틴")
            @RequestParam String ingredientA,
            @Parameter(description = "성분 B. A와 똑같이 입력하면 중복(노랑)으로 판정된다.", example = "이트라코나졸")
            @RequestParam String ingredientB) {
        return ingredientCheckService.checkPair(ingredientA, ingredientB);
    }
}
