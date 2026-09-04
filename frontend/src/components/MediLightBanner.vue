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

const reason = computed(() => {
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
  <section class="medilight-banner" :class="analysis.status.toLowerCase()" aria-labelledby="medilight-heading">
    <SignalLamp :status="analysis.status" large />
    <div class="medilight-copy">
      <b id="medilight-heading">{{ statusLabel }}</b>
      <p>{{ reason }}</p>
    </div>
    <RouterLink v-if="analysis.findings?.length" class="button ghost compact-button" to="/medilight">
      {{ actionLabel }}
    </RouterLink>
  </section>
</template>
