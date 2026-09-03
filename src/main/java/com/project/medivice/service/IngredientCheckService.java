package com.project.medivice.service;

import com.project.medivice.dto.ConflictDto;
import com.project.medivice.dto.IngredientAnalysisDto;
import com.project.medivice.dto.IngredientCheckRequest;
import com.project.medivice.dto.IngredientCheckResponse;
import com.project.medivice.dto.IngredientCheckRequest.Item;
import com.project.medivice.dto.IngredientPairCheckResponse;
import com.project.medivice.dto.IngredientSourceDto;
import com.project.medivice.dto.IngredientSummaryDto;
import com.project.medivice.dto.MedilightPairCheckResponse;
import com.project.medivice.repository.IngredientRepository;
import com.project.medivice.repository.IngredientRepository.DailyLimitRow;
import com.project.medivice.repository.IngredientRepository.EffectOverlapRow;
import com.project.medivice.repository.IngredientRepository.PairRuleRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * "이 성분들을 같이 먹어도 되는가?"를 실제 등록 없이 바로 확인하는 도구.
 * MedilightService와 같은 DUR 원천 테이블(dur_pair_rules·ingredient_effect_groups·
 * ingredient_daily_limits)을 그대로 재사용한다 — 판정 규칙이 두 군데서 따로 관리되면 언젠가
 * 어긋나므로, 여기서는 "특정 사용자의 등록된 약" 대신 "이번 요청에 적은 이름들"을
 * v_active_ingredients 자리에 즉석으로 대입한다고 보면 된다.
 *
 * 다만 dur_single_rules(임부금기·연령금기 등)는 사용자의 생년월일·임신 여부가 있어야 판정되는데
 * 여기엔 특정 사용자가 없으므로 다루지 않는다 — 응답의 note에 그 한계를 명시한다.
 */
@Service
public class IngredientCheckService {

    private static final String DUR_REFERENCE = "식약처 DUR(용량주의) · 의약품안전나라";

    private final IngredientRepository ingredientRepository;

    public IngredientCheckService(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
    }

    public List<IngredientSummaryDto> search(String query, int limit) {
        return ingredientRepository.search(query, limit);
    }

    private record Resolved(Long id, String nameKo, String nameEn, String inputName, BigDecimal amount, String unit) {
    }

    /**
     * 이름 해석 결과. 검색(GET /api/ingredients)은 부분 일치라 "덱시부프로펜"으로도 찾아지는데,
     * 충돌 확인은 원래 정확히 일치하는 이름만 받아들여서 같은 값을 넣어도 "찾지 못함"으로 빠지는
     * 간극이 있었다 — byName은 그 간극을 메운 최종 결과(정확히 일치 + 부분 일치가 하나뿐인 경우
     * 자동 채택), autoMatched는 "무엇을 무엇으로 자동 매칭했는지"를 사용자에게 그대로 보여주기 위한
     * 기록, dedupResolved는 마스터에 같은 이름이 중복 등록돼 있어 DUR 데이터가 연결된 쪽을 골랐다는
     * 기록, ambiguous는 후보가 여러 개(또는 중복 이름 둘 다/둘 다 아닌 경우)라 추측하지 않고
     * 되물어야 하는 경우다.
     */
    private record NameResolution(
            Map<String, IngredientSummaryDto> byName,
            List<String> unresolved,
            Map<String, String> autoMatched,
            Map<String, String> dedupResolved,
            Map<String, List<String>> ambiguous) {
    }

    private NameResolution resolveNames(List<String> requestedNames) {
        Map<String, List<IngredientSummaryDto>> exactByName = new LinkedHashMap<>();
        for (IngredientSummaryDto s : ingredientRepository.findByNames(requestedNames)) {
            exactByName.computeIfAbsent(s.nameKo(), k -> new ArrayList<>()).add(s);
        }

        Map<String, IngredientSummaryDto> byName = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        Map<String, String> autoMatched = new LinkedHashMap<>();
        Map<String, String> dedupResolved = new LinkedHashMap<>();
        Map<String, List<String>> ambiguous = new LinkedHashMap<>();

        for (String name : requestedNames) {
            List<IngredientSummaryDto> exactMatches = exactByName.get(name);
            if (exactMatches != null && exactMatches.size() == 1) {
                byName.put(name, exactMatches.get(0));
                continue;
            }
            if (exactMatches != null && exactMatches.size() > 1) {
                // 마스터에 같은 이름이 두 번 이상 등록된 경우다(데이터 파이프라인 임포트 중복,
                // 예: "나프록센" 252·905번). 둘 중 실제 DUR 규칙이 연결된 쪽만 남기고, 그런
                // 쪽이 정확히 하나가 아니면(둘 다 있거나 둘 다 없으면) 어느 쪽인지 추측하지 않는다.
                List<Long> ids = exactMatches.stream().map(IngredientSummaryDto::id).toList();
                Set<Long> withData = ingredientRepository.findIdsWithDurData(ids);
                List<IngredientSummaryDto> linked = exactMatches.stream()
                        .filter(s -> withData.contains(s.id())).toList();
                if (linked.size() == 1) {
                    IngredientSummaryDto chosen = linked.get(0);
                    byName.put(name, chosen);
                    dedupResolved.put(name, chosen.ingrCode());
                } else {
                    ambiguous.put(name, exactMatches.stream()
                            .map(s -> s.nameKo() + " (코드 " + s.ingrCode() + ")").toList());
                    unresolved.add(name);
                }
                continue;
            }
            List<IngredientSummaryDto> candidates = ingredientRepository.searchByPartialName(name);
            if (candidates.size() == 1) {
                // 후보가 정확히 하나뿐일 때만 자동 채택한다 — 두 개 이상이면(예: "부프로펜"이
                // 이부프로펜·덱시부프로펜 디.씨. 둘 다에 걸림) 어느 쪽인지 추측하지 않는다.
                IngredientSummaryDto only = candidates.get(0);
                byName.put(name, only);
                autoMatched.put(name, only.nameKo());
            } else if (candidates.size() > 1) {
                ambiguous.put(name, candidates.stream().map(IngredientSummaryDto::nameKo).toList());
                unresolved.add(name);
            } else {
                unresolved.add(name);
            }
        }
        return new NameResolution(byName, unresolved, autoMatched, dedupResolved, ambiguous);
    }

    /** resolveNames의 autoMatched·dedupResolved·ambiguous·unresolved를 사람이 읽을 문장으로 붙인다. */
    private static String describeResolution(NameResolution r) {
        StringBuilder sb = new StringBuilder();
        if (!r.autoMatched().isEmpty()) {
            r.autoMatched().forEach((input, matched) -> {
                if (!input.equals(matched)) {
                    sb.append(" \"").append(input).append("\"은(는) 마스터의 \"").append(matched)
                            .append("\"(으)로 자동 매칭했습니다.");
                }
            });
        }
        if (!r.dedupResolved().isEmpty()) {
            r.dedupResolved().forEach((input, code) -> sb.append(" \"").append(input)
                    .append("\"은(는) 마스터에 같은 이름이 중복 등록돼 있어 DUR 데이터가 연결된 항목(코드 ")
                    .append(code).append(")으로 판정했습니다."));
        }
        if (!r.ambiguous().isEmpty()) {
            r.ambiguous().forEach((input, candidates) -> sb.append(" \"").append(input)
                    .append("\"에 해당하는 후보가 여러 개라 추측하지 않았습니다: ")
                    .append(String.join(", ", candidates)).append('.'));
        }
        List<String> trulyUnresolved = r.unresolved().stream()
                .filter(n -> !r.ambiguous().containsKey(n)).toList();
        if (!trulyUnresolved.isEmpty()) {
            sb.append(" 다음 성분은 DUR 마스터에서 찾지 못해 판정하지 못했습니다: ")
                    .append(String.join(", ", trulyUnresolved)).append('.');
        }
        return sb.toString();
    }

    public IngredientCheckResponse check(IngredientCheckRequest request) {
        List<String> requestedNames = request.ingredients().stream().map(Item::name).distinct().toList();
        NameResolution resolution = resolveNames(requestedNames);
        Map<String, IngredientSummaryDto> byName = resolution.byName();
        List<String> unresolved = resolution.unresolved();

        List<Resolved> resolvedItems = new ArrayList<>();
        for (Item item : request.ingredients()) {
            IngredientSummaryDto s = byName.get(item.name());
            if (s != null) {
                resolvedItems.add(new Resolved(s.id(), s.nameKo(), s.nameEn(), item.name(), item.amount(), item.unit()));
            }
        }

        // 입력 순서를 그대로 유지한 성분 id 집합 — 같은 이름을 두 번 넣으면 여기서는 한 번만 세지만,
        // "중복 여부"는 아래 성분별 그룹핑에서 rows.size() >= 2로 따로 판정한다.
        Set<Long> ids = new LinkedHashSet<>();
        for (Resolved r : resolvedItems) {
            ids.add(r.id());
        }

        Map<Long, DailyLimitRow> limits = new LinkedHashMap<>();
        for (DailyLimitRow row : ingredientRepository.findDailyLimits(List.copyOf(ids))) {
            limits.put(row.ingredientId(), row);
        }

        Map<Long, List<Resolved>> byId = new LinkedHashMap<>();
        for (Resolved r : resolvedItems) {
            byId.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(r);
        }

        List<IngredientAnalysisDto> totals = buildTotals(byId, limits);
        List<IngredientAnalysisDto> findings = totals.stream().filter(t -> !"OK".equals(t.status())).toList();
        List<ConflictDto> conflicts = buildConflicts(ids, byId);

        boolean anyCrit = findings.stream().anyMatch(f -> "CRIT".equals(f.status()))
                || conflicts.stream().anyMatch(c -> "CRIT".equals(c.level()));
        boolean anyWarn = !findings.isEmpty() || !conflicts.isEmpty();
        String status = anyCrit ? "CRIT" : anyWarn ? "WARN" : "OK";
        int issueCount = findings.size() + conflicts.size();
        String summary = switch (status) {
            case "CRIT" -> "전문가 확인이 필요한 조합 " + issueCount + "건";
            case "WARN" -> "확인이 필요한 조합 " + issueCount + "건";
            default -> "입력한 성분 조합에서 확인된 문제 없음";
        };

        String note = "임부금기·연령금기처럼 사용자 개인 조건이 필요한 판정은 이 도구에 포함되지 않습니다"
                + "(실제 등록된 사용자가 아니라 이번 요청의 성분만 확인합니다)."
                + describeResolution(resolution);

        return new IngredientCheckResponse(status, summary, findings, conflicts, unresolved, note);
    }

    /**
     * 성분 두 개의 "중복"(같은 성분)과 "충돌"(병용금기·효능군중복) 판정을 한 번에 계산한다.
     * checkPair(메디라이트 미리보기)와 checkIngredientPair(성분 화면의 중복/충돌 배지)가
     * 이 결과를 각자 다른 응답 모양으로 옮겨 담는다 — 이름 해석·DB 조회가 두 곳에서
     * 따로 반복되지 않도록 판정 로직 자체는 한 곳에 둔다.
     */
    private record PairJudgment(
            boolean duplicate, boolean conflict, String conflictType, String severity,
            String detail, String labelA, String labelB, List<String> unresolvedNames) {
    }

    private PairJudgment judgePair(String nameA, String nameB) {
        List<String> requestNames = nameA.equals(nameB) ? List.of(nameA) : List.of(nameA, nameB);
        NameResolution resolution = resolveNames(requestNames);
        Map<String, IngredientSummaryDto> byName = resolution.byName();

        IngredientSummaryDto a = byName.get(nameA);
        IngredientSummaryDto b = byName.get(nameB);
        String labelA = a != null ? a.nameKo() : nameA;
        String labelB = b != null ? b.nameKo() : nameB;

        if (a == null || b == null) {
            String detail = "DUR 마스터에 없는 성분이 있어 판정할 수 없습니다." + describeResolution(resolution);
            return new PairJudgment(false, false, null, null, detail.trim(), labelA, labelB, resolution.unresolved());
        }
        if (a.id().equals(b.id())) {
            return new PairJudgment(true, false, null, null,
                    "같은 성분입니다 — 두 제품에 겹쳐 들어 있으면 하루 총량이 중복 합산되어 과다복용 위험이 있습니다.",
                    labelA, labelB, List.of());
        }

        List<Long> ids = List.of(a.id(), b.id());
        PairRuleRow worstPair = null;
        for (PairRuleRow r : ingredientRepository.findPairRulesAmong(ids)) {
            if (worstPair == null || "RED".equals(r.severity())) {
                worstPair = r;
            }
        }
        if (worstPair != null) {
            return new PairJudgment(false, true, worstPair.durTypeName(), worstPair.severity(),
                    worstPair.prohibitContent(), labelA, labelB, List.of());
        }

        List<EffectOverlapRow> effectOverlaps = ingredientRepository.findEffectOverlapsAmong(ids);
        if (!effectOverlaps.isEmpty()) {
            EffectOverlapRow r = effectOverlaps.get(0);
            return new PairJudgment(false, true, "효능군중복", "YELLOW",
                    r.effectName() + " 계열이 중복됩니다.", labelA, labelB, List.of());
        }

        // 병용금기·효능군중복 어느 규칙에도 안 걸렸다고 해서 "안전하다"고 단정하지 않는다 —
        // 식약처 DUR 마스터가 다루는 조합에만 규칙이 존재하므로, 마스터에 없는(흔치 않은) 조합은
        // 규칙이 없을 뿐 위험 여부를 판단한 게 아니라는 점을 그대로 밝힌다.
        StringBuilder detail = new StringBuilder(
                "확인된 문제 없음 — 식약처 DUR 마스터에 이 조합에 대한 병용금기·효능군중복 규칙이 없습니다."
                        + " 마스터에 없는 조합은 위험 여부를 별도로 판단하지 않으니, 흔치 않은 조합이면 의사·약사에게 추가로 확인하세요.");

        // "케토롤락"(성분 자체 표기)과 "케토롤락트로메타민염"(DUR 규칙이 실제로 걸려 있는 염 표기)처럼,
        // 마스터에 같은 약의 다른 표기가 별개 행으로 들어있어 규칙이 한쪽에만 연결된 경우가 있다.
        // 지금 판정에 쓰인 이름이 병용금기·효능군중복 어디에도 안 걸리는 "고아" 행이면(단일금기만
        // 있는 경우는 pair-check 입장에서 여전히 고아다), 그 사실을 밝혀서 "규칙이 없다"와
        // "이 표기로는 규칙을 찾을 근거 자체가 빈약하다"를 구분해 준다.
        Set<Long> withData = ingredientRepository.findIdsWithPairOrEffectData(ids);
        List<String> orphanLabels = new ArrayList<>();
        if (!withData.contains(a.id())) {
            orphanLabels.add(labelA);
        }
        if (!withData.contains(b.id())) {
            orphanLabels.add(labelB);
        }
        if (!orphanLabels.isEmpty()) {
            detail.append(" 다만 \"").append(String.join("\", \"", orphanLabels))
                    .append("\"은(는) 마스터에 병용금기·효능군중복 등 DUR 연관 데이터가 전혀 등록돼 있지 않은 항목입니다 — ")
                    .append("같은 성분의 다른 표기(염·수화물 등)로 규칙이 등록돼 있을 수 있으니 정확한 제품 성분명으로 다시 확인해 보세요.");
        }

        detail.append(describeResolution(resolution));
        return new PairJudgment(false, false, null, null, detail.toString().trim(), labelA, labelB, List.of());
    }

    /**
     * 메디라이트 색 로직을 성분 딱 두 개로만 즉석 확인한다("두 칸 입력 → 색 확인").
     * 우선순위: 같은 성분(중복) → 노랑. 병용금기(RED) → 빨강. 효능군중복/그 외 병용 규칙(YELLOW) →
     * 노랑. 아무 규칙도 없으면 초록. 마스터에 없는 이름은 unresolvedNames에 담고 판정하지 않는다.
     */
    public MedilightPairCheckResponse checkPair(String nameA, String nameB) {
        PairJudgment j = judgePair(nameA, nameB);
        if (!j.unresolvedNames().isEmpty()) {
            return new MedilightPairCheckResponse("OK", "초록", null, j.detail(), j.labelA(), j.labelB(),
                    j.unresolvedNames());
        }
        if (j.duplicate()) {
            return new MedilightPairCheckResponse("WARN", "노랑", "DUPLICATE", j.detail(), j.labelA(), j.labelB(),
                    List.of());
        }
        if (j.conflict()) {
            return new MedilightPairCheckResponse(mapLevel(j.severity()), "RED".equals(j.severity()) ? "빨강" : "노랑",
                    j.conflictType(), j.detail(), j.labelA(), j.labelB(), List.of());
        }
        return new MedilightPairCheckResponse("OK", "초록", null, j.detail(), j.labelA(), j.labelB(), List.of());
    }

    /**
     * 성분 두 개를 넣으면 "중복"(같은 성분)과 "충돌"(병용금기·효능군중복) 여부를 한 번의 호출로
     * 같이 보여준다. 원래는 duplicate-check/conflict-check 두 엔드포인트로 나눠져 있었는데,
     * 화면에서 성분 두 칸을 한 번만 확인하면 되도록 하나로 합쳐 달라는 요청에 따라 병합했다 —
     * isDuplicate·hasConflict를 각각의 필드로 남겨서 "겹치면 노랑, 충돌나면 빨강" 배지를
     * 프론트에서 그대로 나눠 그릴 수 있게 한다.
     */
    public IngredientPairCheckResponse checkIngredientPair(String nameA, String nameB) {
        PairJudgment j = judgePair(nameA, nameB);
        String colorLabel;
        if (j.conflict()) {
            colorLabel = "RED".equals(j.severity()) ? "빨강" : "노랑";
        } else if (j.duplicate()) {
            colorLabel = "노랑";
        } else {
            colorLabel = "초록";
        }
        return new IngredientPairCheckResponse(j.duplicate(), j.conflict(), j.conflictType(), colorLabel,
                j.detail(), j.labelA(), j.labelB(), j.unresolvedNames());
    }

    private List<IngredientAnalysisDto> buildTotals(Map<Long, List<Resolved>> byId, Map<Long, DailyLimitRow> limits) {
        List<IngredientAnalysisDto> totals = new ArrayList<>();
        for (Map.Entry<Long, List<Resolved>> entry : byId.entrySet()) {
            List<Resolved> rows = entry.getValue();
            Resolved first = rows.get(0);
            BigDecimal total = rows.stream()
                    .map(Resolved::amount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<IngredientSourceDto> sources = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Resolved r = rows.get(i);
                sources.add(new IngredientSourceDto("입력 #" + (i + 1) + " (" + r.inputName() + ")", r.amount(), null, null));
            }

            DailyLimitRow limit = limits.get(entry.getKey());
            boolean isDuplicate = rows.size() >= 2;
            String status;
            String reasonCode;
            BigDecimal upperLimit = null;
            Double ratio = null;
            String unit = first.unit();
            String reference = null;

            if (limit != null) {
                upperLimit = limit.maxQty();
                unit = limit.unit() != null ? limit.unit() : first.unit();
                ratio = upperLimit != null && upperLimit.signum() != 0
                        ? total.doubleValue() / upperLimit.doubleValue() : null;
                reference = DUR_REFERENCE;
                if (upperLimit != null && total.compareTo(upperLimit) > 0) {
                    status = "CRIT";
                    reasonCode = "OVER_LIMIT";
                } else if (isDuplicate) {
                    status = "WARN";
                    reasonCode = "DUPLICATE";
                } else if (ratio != null && ratio >= 0.8) {
                    status = "WARN";
                    reasonCode = "NEAR_LIMIT";
                } else {
                    status = "OK";
                    reasonCode = null;
                }
            } else if (isDuplicate) {
                status = "WARN";
                reasonCode = "DUPLICATE";
            } else {
                status = "OK";
                reasonCode = null;
            }

            totals.add(new IngredientAnalysisDto(first.nameKo(), first.nameEn(), unit, total, sources,
                    status, reasonCode, upperLimit, reference, ratio));
        }
        return totals;
    }

    private List<ConflictDto> buildConflicts(Set<Long> ids, Map<Long, List<Resolved>> byId) {
        List<ConflictDto> conflicts = new ArrayList<>();
        if (ids.size() < 2) {
            return conflicts;
        }
        List<Long> idList = List.copyOf(ids);
        for (PairRuleRow r : ingredientRepository.findPairRulesAmong(idList)) {
            conflicts.add(new ConflictDto(r.durTypeName(), mapLevel(r.severity()),
                    nameOf(byId, r.ingredientAId()), nameOf(byId, r.ingredientBId()), null, null, r.prohibitContent()));
        }
        for (EffectOverlapRow r : ingredientRepository.findEffectOverlapsAmong(idList)) {
            conflicts.add(new ConflictDto("효능군중복", "WARN",
                    nameOf(byId, r.ingredientAId()), nameOf(byId, r.ingredientBId()), null, null,
                    r.effectName() + " 계열이 중복됩니다."));
        }
        return conflicts;
    }

    private static String nameOf(Map<Long, List<Resolved>> byId, Long id) {
        List<Resolved> rows = byId.get(id);
        return rows == null || rows.isEmpty() ? null : rows.get(0).nameKo();
    }

    /** DB 뷰의 색 어휘(RED/YELLOW/INFO)를 프론트 계약의 상태 어휘(OK/WARN/CRIT)로 옮긴다. */
    private static String mapLevel(String severity) {
        if (severity == null) {
            return "OK";
        }
        return switch (severity) {
            case "RED" -> "CRIT";
            case "YELLOW" -> "WARN";
            default -> "OK";
        };
    }
}
