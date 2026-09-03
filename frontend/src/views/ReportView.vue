<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import SignalLamp from '@/components/SignalLamp.vue'
import { useMediviceStore } from '@/stores/medivice'

const { mode } = defineProps({
  mode: { type: String, default: 'create' },
})

const router = useRouter()
const store = useMediviceStore()
const period = ref('month')
const reportLanguage = ref('KO')
const customFrom = ref('2026-08-03')
const customTo = ref('2026-09-02')

const reportRange = computed(() => {
  if (period.value === 'two-weeks') return { from: '2026-08-20', to: '2026-09-02' }
  if (period.value === 'custom') return { from: customFrom.value, to: customTo.value }
  return { from: '2026-08-03', to: '2026-09-02' }
})
const safetyFinding = computed(() => store.medilight.findings[0])

onMounted(() => store.loadDashboard())

async function createReport() {
  const result = await store.createReport({ ...reportRange.value, language: reportLanguage.value })
  if (result) router.push('/report/latest')
}

function printReport() {
  window.print()
}
</script>

<template>
  <AppShell :title="mode === 'result' ? '진료용 복약 보고서' : '진료용 보고서'" :crumb="mode === 'result' ? '진료 보고서 /' : ''">
    <form v-if="mode === 'create'" class="report-builder" @submit.prevent="createReport">
      <section class="content-card report-options">
        <div class="section-title"><div><p class="eyebrow">SCR-RPT-001 · UC28</p><h2>어떤 내용을 정리할까요?</h2><p>복약 목록·등록 사유·증상 기록·안전 이벤트를 한 장으로 정리합니다.</p></div><span class="chip ai">MOCK JSON</span></div>
        <fieldset class="form-field"><legend>기간</legend><div class="selection-grid three"><button type="button" :class="{ selected: period === 'two-weeks' }" @click="period = 'two-weeks'"><b>최근 2주</b><span>08-20 ~ 09-02</span></button><button type="button" :class="{ selected: period === 'month' }" @click="period = 'month'"><b>최근 1개월</b><span>08-03 ~ 09-02</span></button><button type="button" :class="{ selected: period === 'custom' }" @click="period = 'custom'"><b>직접 선택</b><span>날짜 지정</span></button></div></fieldset>
        <div v-if="period === 'custom'" class="field-grid two"><label class="form-field"><span>시작일</span><input v-model="customFrom" type="date" /></label><label class="form-field"><span>종료일</span><input v-model="customTo" type="date" /></label></div>
        <fieldset class="form-field"><legend>표시 언어</legend><div class="selection-grid two"><button type="button" :class="{ selected: reportLanguage === 'KO' }" @click="reportLanguage = 'KO'"><b>한국어</b><span>성분명에 영문 병기</span></button><button type="button" :class="{ selected: reportLanguage === 'EN' }" @click="reportLanguage = 'EN'"><b>English</b><span>해외 진료용</span></button></div></fieldset>
        <div class="include-list"><b>포함 항목</b><ul><li>복약 목록과 등록 사유</li><li>증상 기록 타임라인</li><li>중복·상한 안전 이벤트</li><li>의료진에게 물어볼 질문</li></ul></div>
        <button class="button primary wide" type="submit" :disabled="store.reportStatus === 'PROCESSING'"><span v-if="store.reportStatus === 'PROCESSING'" class="spinner small"></span>{{ store.reportStatus === 'PROCESSING' ? '보고서 정리 중' : '보고서 만들기' }}</button>
        <p class="status-pipeline">STATUS · PENDING → PROCESSING → COMPLETED / FAILED</p>
      </section>
    </form>

    <template v-else>
      <div class="report-toolbar"><div><span class="chip ok">COMPLETED</span><span>{{ store.latestReport?.from ?? '2026-08-03' }} ~ {{ store.latestReport?.to ?? '2026-09-02' }}</span></div><button class="button ghost" type="button" @click="printReport">인쇄</button></div>
      <article class="medical-report">
        <header class="report-header"><div><b>복약 현황 보고서</b><span>{{ store.user?.name }} · {{ store.user?.sex }} · {{ store.user?.age }}세 · {{ store.user?.conditions?.join(' · ') }}</span></div><div><span>기간 2026-08-03 ~ 2026-09-02</span><span>생성 2026-09-02 21:44</span><b>사용자 확인 완료</b></div></header>
        <section class="report-section"><h2>현재 복용 중 · {{ store.medications.length }}건</h2><div class="table-wrap"><table class="data-table report-table"><thead><tr><th>구분</th><th>제품명</th><th>성분 (영문)</th><th>용법</th><th>기간</th><th>등록 사유</th></tr></thead><tbody><tr v-for="medication in store.medications" :key="medication.id"><td><span class="chip" :class="medication.type === 'PRESCRIPTION' ? 'rx' : 'neutral'">{{ medication.type === 'PRESCRIPTION' ? '처방' : medication.type === 'OTC' ? '상비약' : '영양제' }}</span></td><td><b>{{ medication.name }}</b></td><td>{{ medication.ingredients.map((ingredient) => `${ingredient.englishName} ${ingredient.amount.toLocaleString()} ${ingredient.unit}`).join(' · ') }}</td><td>{{ medication.timesPerDay ? `${medication.dose}${medication.doseUnit} × ${medication.timesPerDay}회` : `필요 시 최대 ${medication.maxTimesPerDay}회` }}</td><td>{{ medication.startDate }} ~</td><td>{{ medication.reason }}</td></tr></tbody></table></div></section>
        <section class="report-section"><h2>안전 이벤트 · {{ store.medilight.findings.length }}건</h2><div v-if="safetyFinding" class="report-safety"><SignalLamp :status="safetyFinding.status" /><div><b>{{ safetyFinding.ingredient }} 성분 {{ safetyFinding.reasonCode === 'DUPLICATE' ? '중복' : '기준 확인' }}</b><span>{{ safetyFinding.sources.map((source) => source.product).join(' + ') }} = 하루 {{ safetyFinding.dailyTotal.toLocaleString() }} {{ safetyFinding.unit }} · 적용 상한 {{ safetyFinding.upperLimit?.toLocaleString() }} {{ safetyFinding.unit }}</span></div></div><p v-else class="helper-copy">현재 적재된 규칙에서 확인된 이벤트가 없습니다.</p></section>
        <section class="report-section"><h2>증상 기록 타임라인 · {{ store.symptoms.length }}건</h2><div class="timeline"><div v-for="entry in store.symptoms" :key="entry.id" class="timeline-event"><time>{{ entry.date }} {{ entry.time }}</time><b>{{ entry.symptoms.join(', ') }}</b><p>{{ entry.note }} · 당시 복용 {{ entry.medicationSnapshot.length }}건</p></div></div></section>
        <footer class="report-disclaimer">이 보고서는 사용자가 직접 입력·확인한 기록을 정리한 것으로, 증상과 약의 인과관계를 판정하지 않습니다. 진단·처방 변경의 근거로 사용할 수 없으며 의료전문가의 판단을 대신하지 않습니다. 성분 기준 출처: 2020 한국인 영양소 섭취기준 (화면 시연용 샘플, 확인일 2026-09-02).</footer>
      </article>
    </template>
  </AppShell>
</template>
