# 메디바이스 · 화면 요소 ↔ DB 매핑

> Figma 파일: 메디바이스 UI/UX 와이어프레임 · 페이지 `03 · 화면 플로우 & 데이터 매핑`
> 기준 스키마: `DA_데이터파이프라인/dbml/광주2_1조_메디바이스-DB.dbml`
> Layer A(공공 API 참조)와 Layer B(서비스 데이터)는 오직 `ingredients` 하나로 만납니다.

| 화면 · 요소 | DB 테이블 · 컬럼 | UC |
|---|---|---|
| 메인 · 복용 목록 그룹 | `prescriptions` + `departments` + `medications` + `products` | UC18 |
| 메인 · 메디라이트 배너 | `safety_checks.level` (GREEN/YELLOW/RED) · `uncovered_count` | UC16 |
| 메디라이트 · 합산 근거 | `medications` × `product_ingredients` × `ingredients` (성분 단위 합산) | UC15 |
| 메디라이트 · 상한 비교 막대 | `ingredient_daily_limits.max_qty / unit / age_group` | UC15 |
| 메디라이트 · 중복 판정 | `dur_pair_rules` · `ingredient_effect_groups` · `effect_groups` | UC15 |
| 메디라이트 · 성분별 상태 표 | `safety_check_items` (dur_type_id, total_amount, threshold, level) | UC15 |
| AI 설명 박스 `.ai-box` | `ai_outputs` (target_type='SAFETY_CHECK', result_json, status) | UC17 |
| 인식 결과 확인 · 값 | `prescriptions.source='OCR'` · `medications.source` · `department_id` | UC9·10·12 |
| 인식 결과 확인 · 신뢰도 | **DB 미저장** — OCR API 응답의 confidence를 화면에만 노출 (D-4) | UC12 |
| 수기 등록 폼 | `medications.custom_name` + `medication_ingredients` (amount, unit) | UC13 |
| 복용 목록 · 삭제 | `medications.ended_at` 소프트 삭제 — 이력·스냅샷 보존 | UC14 |
| 증상 기록 · 증상 선택 | `side_effect_logs` + `side_effect_symptoms` + `symptoms(category)` | UC20 |
| 증상 기록 · 복용 스냅샷 | `side_effect_snapshots` (product_name, ingredient_text 값 복사) | UC21 |
| 판정 불가 고지 | `notice_templates` + `safety_check_items.level='INFO'` (색은 안 올림) | UC31 |
| KO / EN 토글 | `users.lang` · `ingredients.name_en` · `notice_templates.lang` | UC27 |

## 와이어프레임에서 임시로 처리한 것 — 구현 전 결정 필요

- **Q-1 노랑·빨강 판정 임계값** — 지금 화면은 “상한 4,000 IU 대비 2,400 IU(60%) + 중복 → 노랑”으로 그렸습니다. 갭 분석 초안(상한 초과=빨강, 80% 이상 또는 중복=노랑) 확정 필요.
- **Q-2 처방약 성분도 상한 비교 대상인가** — 메디라이트 상세 표에서 처방약은 “처방 용량 내”로 회피. 제외하는 쪽을 권합니다.
- **Q-3 “필요 시” 복용 약의 하루 총량** — 타이레놀은 “필요 시 / 하루 최대 3회”로만 표기하고 합산에서 제외.
- **Q-5 한·영 전환 범위** — KO/EN 토글은 그렸지만 비활성. `users.lang`·`ingredients.name_en` 데이터 출처 미정.
- **Q-6 반응형 범위** — 와이어프레임은 데스크톱 1180px 고정. 모바일까지 하면 레일 → 상단 탭 전환 작업 추가.
- **Q-7 복용 항목 삭제의 의미** — `ended_at` 소프트 삭제로 그림. 화면에서 “삭제”와 “복용 종료”를 구분할지 결정 필요.
