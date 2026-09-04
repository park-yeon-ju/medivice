package com.project.medivice.dto;

/**
 * v_uncovered_ingredients(성분은 등록됐지만 병용금기·효능군중복·1일상한·단일금기 어느 DUR
 * 규칙에도 연결 안 된 성분)의 성분 한 건. medilight.noticeMessage(콤마로 성분명을 이어붙인
 * 한 문장)와 같은 데이터를 담지만, 프론트가 이걸 문장 대신 성분별 표로 나눠 보여줄 수 있게
 * 구조화된 형태로도 같이 내려준다(TROUBLESHOOTING.md §37 — "이것도 각 성분을 나눠서
 * 이해해야지"라는 요청으로 추가됨).
 */
public record UncoveredIngredientDto(String name, String englishName) {
}
