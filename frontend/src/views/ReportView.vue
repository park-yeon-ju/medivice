<!--
  ReportView.vue
  복용·증상·메디라이트 정보를 기간별로 묶어 진료 시 보여줄 수 있는 메디 레포트를 생성하고 표시한다.

  규칙 결과와 사용자가 기록한 사실을 정리할 뿐 진단이나 약과 증상의 인과관계를 판정하지 않는다.
  관련 UC: UC28, UC29 / 화면: SCR-RPT-001, SCR-RPT-002
-->
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

function formatIngredient(ingredient) {
  // 수기 입력 성분은 영문명이 없을 수 있으므로 null 대신 사용자가 확인한 한글 성분명을 보여준다.
  const name = ingredient.englishName || ingredient.name || '성분명 미확인'
  const amount = ingredient.amount?.toLocaleString?.() ?? '함량 미확인'
  return [name, amount, ingredient.unit].filter(Boolean).join(' ')
}

function formatDosage(medication) {
  if (medication.timesPerDay) {
    return `${medication.dose}${medication.doseUnit} × ${medication.timesPerDay}회`
  }
  // 횟수 정보가 없을 때 임의의 복용 상한을 만들어 안내하지 않는다.
  return medication.maxTimesPerDay
    ? `필요 시 최대 ${medication.maxTimesPerDay}회`
    : '필요 시 · 횟수 정보 없음'
}
</script>

<template>
  <AppShell title="메디 레포트">
    <form v-if="mode === 'create'" class="report-builder" @submit.prevent="createReport">
      <section class="content-card report-options">
        <div class="section-title">
          <div><h2>메디 레포트</h2></div>
        </div>
        <fieldset class="form-field">
          <legend>기간</legend>
          <div class="selection-grid three">
            <button
              type="button"
              :class="{ selected: period === 'two-weeks' }"
              @click="period = 'two-weeks'"
            >
              <b>최근 2주</b><span>08-20 ~ 09-02</span></button
            ><button
              type="button"
              :class="{ selected: period === 'month' }"
              @click="period = 'month'"
            >
              <b>최근 1개월</b><span>08-03 ~ 09-02</span></button
            ><button
              type="button"
              :class="{ selected: period === 'custom' }"
              @click="period = 'custom'"
            >
              <b>직접 선택</b><span>날짜 지정</span>
            </button>
          </div>
        </fieldset>
        <div v-if="period === 'custom'" class="field-grid two">
          <label class="form-field"
            ><span>시작일</span><input v-model="customFrom" type="date" /></label
          ><label class="form-field"
            ><span>종료일</span><input v-model="customTo" type="date"
          /></label>
        </div>
        <fieldset class="form-field">
          <legend>표시 언어</legend>
          <div class="selection-grid two">
            <button
              type="button"
              :class="{ selected: reportLanguage === 'KO' }"
              @click="reportLanguage = 'KO'"
            >
              <b>한국어</b></button
            ><button
              type="button"
              :class="{ selected: reportLanguage === 'EN' }"
              @click="reportLanguage = 'EN'"
            >
              <b>English</b>
            </button>
          </div>
        </fieldset>
        <div class="include-list">
          <b>포함 항목</b>
          <ul>
            <li>복용 약물 및 등록 사유</li>
            <li>메디라이트</li>
            <li>증상 기록 타임라인</li>
          </ul>
        </div>
        <button
          class="button primary wide"
          type="submit"
          :disabled="store.reportStatus === 'PROCESSING'"
        >
          <span v-if="store.reportStatus === 'PROCESSING'" class="spinner small"></span
          >{{ store.reportStatus === 'PROCESSING' ? '메디 레포트 정리 중' : '메디 레포트 만들기' }}
        </button>
      </section>
    </form>

    <template v-else>
      <div class="report-toolbar">
        <div>
          <span class="chip ok">COMPLETED</span
          ><span
            >{{ store.latestReport?.from ?? '2026-08-03' }} ~
            {{ store.latestReport?.to ?? '2026-09-02' }}</span
          >
        </div>
        <button class="button ghost" type="button" @click="printReport">인쇄</button>
      </div>
      <article class="medical-report">
        <header class="report-header">
          <div>
            <b>메디 레포트</b
            ><span
              >{{ store.user?.name }} · {{ store.user?.sex }} · {{ store.user?.age }}세 ·
              {{ store.user?.conditions?.join(' · ') }}</span
            >
          </div>
          <div>
            <span>기간 2026-08-03 ~ 2026-09-02</span><span>생성 2026-09-02 21:44</span
            ><b>사용자 확인 완료</b>
          </div>
        </header>
        <section class="report-section">
          <h2>현재 복용 중 · {{ store.medications.length }}건</h2>
          <div class="table-wrap">
            <table class="data-table report-table">
              <thead>
                <tr>
                  <th>구분</th>
                  <th>제품명</th>
                  <th>성분 (영문)</th>
                  <th>용법</th>
                  <th>기간</th>
                  <th>등록 사유</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="medication in store.medications" :key="medication.id">
                  <td>
                    <span
                      class="chip"
                      :class="medication.type === 'PRESCRIPTION' ? 'rx' : 'neutral'"
                      >{{
                        medication.type === 'PRESCRIPTION'
                          ? '처방'
                          : medication.type === 'OTC'
                            ? '상비약'
                            : '영양제'
                      }}</span
                    >
                  </td>
                  <td>
                    <b>{{ medication.name }}</b>
                  </td>
                  <td>{{ medication.ingredients.map(formatIngredient).join(' · ') }}</td>
                  <td>{{ formatDosage(medication) }}</td>
                  <td>{{ medication.startDate }} ~</td>
                  <td>{{ medication.reason }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section class="report-section">
          <h2>성분 분석 어드바이스 · {{ store.medilight.findings.length }}건</h2>
          <div v-if="safetyFinding" class="report-safety">
            <SignalLamp :status="safetyFinding.status" />
            <div>
              <b
                >{{ safetyFinding.ingredient }} 성분
                {{ safetyFinding.reasonCode === 'DUPLICATE' ? '중복' : '기준 확인' }}</b
              ><span
                >{{ safetyFinding.sources.map((source) => source.product).join(' + ') }} = 하루
                {{ safetyFinding.dailyTotal.toLocaleString() }} {{ safetyFinding.unit }} · 적용 상한
                {{ safetyFinding.upperLimit?.toLocaleString() }} {{ safetyFinding.unit }}</span
              >
            </div>
          </div>
          <p v-else class="helper-copy">현재 적재된 규칙에서 확인된 이벤트가 없습니다.</p>
        </section>
        <section class="report-section">
          <h2>증상 기록 타임라인 · {{ store.symptoms.length }}건</h2>
          <div class="timeline">
            <div v-for="entry in store.symptoms" :key="entry.id" class="timeline-event">
              <time>{{ entry.date }} {{ entry.time }}</time
              ><b>{{ entry.symptoms.join(', ') }}</b>
              <p>{{ entry.note }} · 당시 복용 {{ entry.medicationSnapshot.length }}건</p>
            </div>
          </div>
        </section>
        <footer class="report-disclaimer">
          이 보고서는 사용자가 직접 입력·확인한 기록을 정리한 것으로, 증상과 약의 인과관계를
          판정하지 않습니다. 진단·처방 변경의 근거로 사용할 수 없으며 의료전문가의 판단을 대신하지
          않습니다. 성분 기준 출처: 2020 한국인 영양소 섭취기준 (화면 시연용 샘플, 확인일
          2026-09-02).
        </footer>
      </article>
    </template>
  </AppShell>
</template>
