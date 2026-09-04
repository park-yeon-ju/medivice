<!--
  MediLightBanner.vue
  메인 화면에서 메디라이트 상태와 가장 중요한 판정 근거를 요약하는 배너.

  병용 충돌이 있으면 단순 성분 합계보다 충돌 건수와 확인 안내를 먼저 보여준다.
  상태별 배너 구조는 동일하게 유지하고 충돌 약·성분·근거는 상세 화면에서 확인하게 한다.

  관련 UC: UC15, UC16 / 화면: SCR-MAIN-001
-->
<script setup>
import { computed } from 'vue'
import SignalLamp from './SignalLamp.vue'

const props = defineProps({
  analysis: { type: Object, required: true },
  actionLabel: { type: String, default: '사유 자세히 보기' },
})

const statusLabel = computed(() => {
  if (props.analysis.status === 'CRIT') return '빨강 · 높은 주의'
  if (props.analysis.status === 'WARN') return '노랑 · 주의'
  return '초록 · 확인된 문제 없음'
})

const conflicts = computed(() => props.analysis.conflicts ?? [])

const reason = computed(() => {
  if (conflicts.value.length) {
    return `함께 확인해야 할 약 조합이 ${conflicts.value.length}건 있습니다. 아래 약과 성분을 의사·약사에게 보여주고 확인하세요.`
  }
  const finding = props.analysis.findings?.[0]
  if (!finding) {
    return '현재 적재된 성분·규칙 범위에서 중복 또는 임계값 문제가 발견되지 않았습니다.'
  }
  if (finding.status === 'CRIT') {
    return `${finding.ingredient} 하루 합계가 설정된 기준을 초과했습니다. 복용을 임의로 바꾸지 말고 전문가에게 확인하세요.`
  }
  if (finding.reasonCode === 'DUPLICATE') {
    return `${finding.ingredient}이(가) ${finding.sources.length}개 제품에 중복되어 하루 ${finding.dailyTotal.toLocaleString()} ${finding.unit}입니다. 설정된 기준 이내라도 확인이 필요합니다.`
  }
  return `${finding.ingredient} 섭취량이 설정된 기준에 근접해 확인이 필요합니다.`
})
</script>

<template>
  <section
    class="medilight-banner"
    :class="analysis.status.toLowerCase()"
    aria-labelledby="medilight-heading"
  >
    <SignalLamp :status="analysis.status" large />
    <div class="medilight-copy">
      <b id="medilight-heading">{{ statusLabel }}</b>
      <p>{{ reason }}</p>
    </div>
    <RouterLink
      v-if="conflicts.length || analysis.findings?.length"
      class="button ghost compact-button"
      to="/medilight"
    >
      {{ actionLabel }}
    </RouterLink>
  </section>
</template>
