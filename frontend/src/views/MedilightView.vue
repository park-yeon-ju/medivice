<script setup>
import { computed, onMounted } from 'vue'
import AppShell from '@/layouts/AppShell.vue'
import SignalLamp from '@/components/SignalLamp.vue'
import { useMediviceStore } from '@/stores/medivice'

const store = useMediviceStore()

const primaryFinding = computed(() => store.medilight.findings[0])
const statusLabel = computed(() => {
  if (store.medilight.status === 'CRIT') return '빨강 · 높은 주의'
  if (store.medilight.status === 'WARN') return '노랑 · 주의'
  return '초록 · 확인된 문제 없음'
})
const meterWidth = computed(() => `${Math.min((primaryFinding.value?.ratio ?? 0) * 100, 100)}%`)

onMounted(() => store.loadDashboard())
</script>

<template>
  <AppShell title="메디라이트 상세" crumb="메인 /">
    <div class="status-page-heading">
      <div class="status-title"><SignalLamp :status="store.medilight.status" large /><div><span>규칙 기반 분석 결과</span><h2>{{ statusLabel }}</h2></div></div>
      <span class="rule-version">규칙 {{ store.medilight.ruleVersion }} · {{ store.medilight.checkedAt }} 적용</span>
    </div>

    <section v-if="primaryFinding" class="finding-card" :class="primaryFinding.status.toLowerCase()">
      <header><div><span class="chip" :class="primaryFinding.status === 'CRIT' ? 'crit' : 'warn'">{{ primaryFinding.reasonCode === 'DUPLICATE' ? `중복 ${primaryFinding.sources.length}건` : '기준 확인' }}</span><h2>{{ primaryFinding.ingredient }}</h2></div><b>{{ primaryFinding.dailyTotal.toLocaleString() }} {{ primaryFinding.unit }} / 일</b></header>
      <div class="formula">하루 성분 섭취량 = 단위당 함량 × 1회 복용 개수 × 하루 복용 횟수</div>
      <div class="evidence-list">
        <div v-for="source in primaryFinding.sources" :key="source.product"><span>{{ source.product }}</span><code>{{ source.amount.toLocaleString() }} {{ primaryFinding.unit }} × {{ source.dose }} × {{ source.timesPerDay }}회</code></div>
        <div class="evidence-total"><span>하루 합계</span><code>{{ primaryFinding.dailyTotal.toLocaleString() }} {{ primaryFinding.unit }}</code></div>
      </div>
      <div v-if="primaryFinding.upperLimit" class="intake-meter">
        <div class="meter-track"><span :class="primaryFinding.status.toLowerCase()" :style="{ width: meterWidth }"></span><i></i></div>
        <div><span>0 {{ primaryFinding.unit }}</span><b>현재 {{ Math.round(primaryFinding.ratio * 100) }}%</b><span>상한 {{ primaryFinding.upperLimit.toLocaleString() }} {{ primaryFinding.unit }}</span></div>
      </div>
      <div class="ai-box">
        <span class="chip ai">AI 설명</span>
        <p>여러 제품에 같은 성분이 들어 있습니다. 합계는 현재 적용된 기준 안에 있지만, 중복 섭취 중이라는 점을 확인하고 다음 상담 때 제품 목록을 보여주세요. 처방약이나 복용량을 임의로 변경하지 마세요.</p>
        <small>출처 {{ primaryFinding.reference }} · 확인일 {{ store.medilight.checkedAt }} · 진단이나 복용 중단 권고가 아닙니다.</small>
      </div>
    </section>

    <section v-else class="empty-state compact-empty"><SignalLamp status="OK" large /><b>현재 규칙에서 확인된 문제 없음</b><p>이 결과는 안전을 보장하지 않으며, 현재 적재된 성분·규칙 범위에 한정됩니다.</p></section>

    <section class="content-card">
      <div class="section-title"><div><span class="section-kicker">INGREDIENT TOTALS</span><h2>성분별 분석 결과</h2></div><span class="chip neutral">{{ store.medilight.totals.length }}종</span></div>
      <div class="table-wrap">
        <table class="data-table">
          <thead><tr><th>성분</th><th>하루 합계</th><th>적용 기준</th><th>상태</th></tr></thead>
          <tbody><tr v-for="total in store.medilight.totals" :key="total.ingredient"><td><b>{{ total.ingredient }}</b></td><td class="numeric">{{ total.dailyTotal.toLocaleString() }} {{ total.unit }}</td><td>{{ total.upperLimit ? `상한 ${total.upperLimit.toLocaleString()} ${total.unit}` : '적재된 기준 없음' }}</td><td><span class="chip" :class="total.status === 'OK' ? 'ok' : total.status === 'CRIT' ? 'crit' : 'warn'">{{ total.status === 'OK' ? '확인된 문제 없음' : total.status === 'CRIT' ? '높은 주의' : '주의' }}</span></td></tr></tbody>
        </table>
      </div>
      <p class="safety-copy">‘확인된 문제 없음’은 안전을 보장한다는 뜻이 아니라, 현재 적재된 성분·규칙 범위에서 문제가 발견되지 않았다는 의미입니다.</p>
    </section>
  </AppShell>
</template>
