package com.project.medivice.dto;

/** GET /api/ingredients 목록/검색 결과 한 줄. 성분 마스터(ingredients)를 그대로 노출한다. */
public record IngredientSummaryDto(Long id, String nameKo, String nameEn, String ingrCode) {
}
