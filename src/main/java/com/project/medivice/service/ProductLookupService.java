package com.project.medivice.service;

import com.project.medivice.repository.IngredientRepository;
import com.project.medivice.repository.IngredientRepository.ProductIngredientRow;
import com.project.medivice.repository.IngredientRepository.ProductInfoSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 제품명 → 성분 조회. OcrService(§26)와 GET /api/products/ingredients(§28)가 둘 다 쓰는
 * "1차 전체 이름 매칭 실패 시 2차 브랜드명 완화 검색(§27)" 로직을 한 곳에만 둔다 — 두 군데서
 * 따로 관리하면 언젠가 어긋난다(IngredientCheckService의 같은 원칙 참고).
 */
@Service
public class ProductLookupService {

    private final IngredientRepository ingredientRepository;

    public ProductLookupService(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
    }

    /**
     * 정확한 이름(또는 "이름(주성분)" 접두어)으로 먼저 찾고, 못 찾으면 "정" 앞부분(브랜드명)만
     * 으로 완화 검색한다 — 단 그 결과가 제품 하나로 안 좁혀지면 포기하고 빈 리스트를 돌려준다
     * (§27: 틀린 용량의 성분을 잘못 붙이는 것보다 못 찾는 게 낫다). 그래도 못 찾으면 3차로,
     * 입력 문자열 안에 성분 마스터의 이름이 부분 문자열로 들어있는지 본다 — "제일메토트렉세이트정"
     * 처럼 제조사명이 성분명보다 앞에 오는 실제 제품명 패턴 때문에 1·2차(접두어 매칭)가 못
     * 잡는 "메토트렉세이트정[2.5mg/1정]" 같은 입력을 구제한다(§33).
     */
    public List<ProductIngredientRow> findIngredients(String productName) {
        List<ProductIngredientRow> rows = ingredientRepository.findIngredientsByProductName(productName);
        if (rows.isEmpty()) {
            rows = ingredientRepository.findIngredientsByCoreName(productName);
        }
        if (rows.isEmpty()) {
            rows = ingredientRepository.findIngredientsByEmbeddedName(productName);
        }
        return rows;
    }

    /** 식약처 e약은요 효능·효과·부작용 원문. §29·§31(약 등록 시 AI 설명)의 근거 텍스트로 쓴다. */
    public Optional<ProductInfoSummary> findProductInfo(String productName) {
        return ingredientRepository.findProductInfoByProductName(productName);
    }
}
