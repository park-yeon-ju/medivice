package com.project.medivice.controller;

import com.project.medivice.dto.IngredientCheckRequest;
import com.project.medivice.dto.IngredientCheckResponse;
import com.project.medivice.dto.IngredientPairCheckResponse;
import com.project.medivice.dto.IngredientSummaryDto;
import com.project.medivice.service.IngredientCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 성분 마스터 조회 + "이 성분들을 같이 먹어도 되는가" 즉석 확인. 등록된 사용자·복용 목록이 없어도
 * 성분 이름만으로 병용금기·효능군중복·중복(과복용) 여부를 바로 볼 수 있다.
 */
@Tag(name = "성분 · 충돌 확인", description = "성분 마스터 조회 및 성분 조합의 병용금기·중복(과복용) 즉석 확인")
@RestController
@RequestMapping("/api/ingredients")
public class IngredientController {

    private final IngredientCheckService ingredientCheckService;

    public IngredientController(IngredientCheckService ingredientCheckService) {
        this.ingredientCheckService = ingredientCheckService;
    }

    @Operation(
            summary = "성분 목록 조회",
            description = "식약처 DUR 성분 마스터(952종)에서 이름으로 검색한다. query를 비우면 이름순 상위 결과만 반환한다.")
    @GetMapping
    public List<IngredientSummaryDto> search(
            @Parameter(description = "성분명 부분 일치 검색어(한글·영문 모두 대상). 비우면 전체에서 상위 결과.")
            @RequestParam(required = false) String query,
            @Parameter(description = "최대 반환 개수")
            @RequestParam(defaultValue = "50") int limit) {
        return ingredientCheckService.search(query, Math.min(limit, 200));
    }

    @Operation(
            summary = "성분 조합 충돌·과복용 확인",
            description = """
                    성분 이름 목록(함량 선택)을 넣으면 실제 등록 없이 바로 확인한다.
                    - 같은 성분명을 두 번 이상 넣으면 "중복(과복용 가능성)"으로 표시된다(findings, reasonCode=DUPLICATE).
                    - 함량 합이 1일 상한을 넘으면 CRIT(reasonCode=OVER_LIMIT), 80% 이상이면 WARN(NEAR_LIMIT).
                    - 서로 다른 두 성분이 병용금기·효능군중복이면 conflicts에 담긴다.
                    - DUR 마스터에 없는 이름은 unresolvedNames에 담기고 판정에서 제외된다(추측하지 않음).
                    - 임부금기·연령금기처럼 사용자 개인 조건이 필요한 판정은 포함하지 않는다(특정 사용자가 없음).
                    """)
    @PostMapping("/check")
    public IngredientCheckResponse check(@Valid @RequestBody IngredientCheckRequest request) {
        return ingredientCheckService.check(request);
    }

    @Operation(
            summary = "성분 두 개 — 중복·충돌 한 번에 확인",
            description = """
                    성분명 두 칸만 채우면 바로 확인한다(JSON 배열 입력 불필요). "중복"과 "충돌"을
                    isDuplicate·hasConflict 두 필드로 같이 내려주므로 화면에서 노랑(중복)·빨강(충돌)
                    배지를 한 번의 호출로 나눠 그릴 수 있다.
                    - **같은 성분을 두 칸에 똑같이 입력** → isDuplicate=true, 노랑
                    - **병용금기(RED)로 등록된 서로 다른 두 성분** → hasConflict=true, 빨강
                    - **효능군중복(YELLOW) 등** → hasConflict=true, 노랑
                    - 아무 규칙에도 안 걸리면 → 둘 다 false, 초록

                    노랑(중복) 테스트 예시: ingredientA=이트라코나졸, ingredientB=이트라코나졸
                    빨강(충돌) 테스트 예시: ingredientA=심바스타틴, ingredientB=이트라코나졸
                    (병용금기, 사유: 횡문근융해증 — 실제 DUR 데이터)""")
    @GetMapping("/pair-check")
    public IngredientPairCheckResponse pairCheck(
            @Parameter(description = "성분 A (정확한 이름 또는 부분 일치로 찾을 수 있는 이름)", example = "심바스타틴")
            @RequestParam String ingredientA,
            @Parameter(description = "성분 B. A와 똑같이 입력하면 중복(노랑)으로 판정된다.", example = "이트라코나졸")
            @RequestParam String ingredientB) {
        return ingredientCheckService.checkIngredientPair(ingredientA, ingredientB);
    }
}
