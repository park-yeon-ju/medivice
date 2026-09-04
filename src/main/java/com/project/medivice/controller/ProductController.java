package com.project.medivice.controller;

import com.project.medivice.dto.IngredientDto;
import com.project.medivice.service.ProductLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §26·§27이 OCR용으로 만든 "제품명 → 성분" 매칭을 독립 API로도 노출한다. 등록된 사용자·복용
 * 목록이 없어도 상용 의약품 이름만으로 그 안에 든 성분을 바로 확인할 수 있다.
 */
@Tag(name = "제품", description = "상용 의약품 제품명으로 성분 조회 (공공데이터포털 허가 원본 데이터, 43,000여 종, §28)")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductLookupService productLookupService;

    public ProductController(ProductLookupService productLookupService) {
        this.productLookupService = productLookupService;
    }

    @Operation(
            summary = "제품명으로 성분 전체 조회",
            description = """
                    상용 의약품 이름을 넣으면 그 제품에 실제로 든 성분(성분명·영문명·함량·단위)을
                    전부 돌려준다. OCR(POST /api/medications/ocr)이 쓰는 것과 같은 매칭 로직이다:

                    1. 정확한 이름(또는 "이름(주성분)" 형태의 접두어)으로 먼저 찾는다.
                    2. 못 찾으면 "정" 앞부분(브랜드명)만으로 완화 검색한다 — 단, 같은 브랜드에
                       용량이 다른 버전이 여러 개 걸려 제품 하나로 안 좁혀지면 포기한다.
                    3. 그래도 못 찾으면, 입력 문자열 안에 성분 마스터(5,000여 종)의 이름이 부분
                       문자열로 들어있는지 본다 — 실제 제품명은 "제일메토트렉세이트정"처럼
                       제조사명이 성분명보다 앞에 오는 경우가 많아 1·2차(접두어 매칭)로는 못
                       찾는, 제조사명 없이 성분명 자체가 제품명인 입력을 구제한다.

                    셋 다 실패하면(DB 수집 범위 밖이거나 모호함) 빈 배열을 돌려준다 — 틀린 용량의
                    성분을 잘못 붙이는 것보다 못 찾는 게 낫다는 원칙(TROUBLESHOOTING.md §12·§26·§27·§33).

                    예시 — 정확한 이름: name=벨록스캡정40밀리그램
                    → [{"name":"펙수프라잔염산염","englishName":"Fexuprazan Hydrochloride","amount":40,"unit":"mg"}]

                    예시 — 용량 없이 "정"까지만(2차 폴백, 이 브랜드의 등록된 용량이 하나뿐이라 성공): name=가드렛정
                    → [{"name":"아나글립틴","englishName":"Anagliptin","amount":100,"unit":"mg"}]

                    예시 — 용량 없이 "정"까지만인데 용량이 여러 개라 모호해서 실패: name=벨록스캡정
                    → [] (10·20·40mg 세 버전이 걸려 하나로 못 좁힘)

                    예시 — 제조사명 없이 성분명 자체가 제품명(3차 폴백, §33): name=메토트렉세이트정[2.5mg/1정]
                    → [{"name":"메토트렉세이트","englishName":"Methotrexate","amount":2.5,"unit":"mg"}]
                    (실제 등록된 21개 제품은 전부 "제일메토트렉세이트정...", "유한메토트렉세이트정" 처럼
                    제조사명이 앞에 붙어 있어 1·2차로는 못 찾지만, 성분 마스터에 "메토트렉세이트"가
                    부분 문자열로 들어있는 걸 확인해 함량까지 뽑아낸다.)

                    예시 — DB 수집 범위 밖: name=타이레놀정500밀리그램
                    → []""")
    @GetMapping("/ingredients")
    public List<IngredientDto> ingredients(
            @Parameter(description = "제품명. 정확한 이름(용량 포함)을 권장하지만, \"정\"까지만 넣어도 그 브랜드의 등록된 용량이 하나뿐이면 찾아진다.",
                    example = "벨록스캡정40밀리그램")
            @RequestParam String name) {
        return productLookupService.findIngredients(name).stream()
                .map(row -> new IngredientDto(row.nameKo(), row.nameEn(), row.amount(), row.unit()))
                .toList();
    }
}
