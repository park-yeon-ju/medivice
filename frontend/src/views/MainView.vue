<!--
  MainView.vue
  로그인한 사용자의 복용 현황과 메디라이트 요약, 주요 등록 동선을 보여주는 메인 화면.

  대시보드 데이터를 한 번 불러와 복용 유형별로 나누며, 상태 정보는 메디라이트 공통 컴포넌트에 맡긴다.
  관련 UC: UC6, UC15, UC16 / 화면: SCR-MAIN-001
-->
<script setup>
import { computed, onMounted } from 'vue'
import AppShell from '@/layouts/AppShell.vue'
import MediLightBanner from '@/components/MediLightBanner.vue'
import MedicationRow from '@/components/MedicationRow.vue'
import { useMediviceStore } from '@/stores/medivice'

const store = useMediviceStore()

const duplicatedIngredients = computed(() =>
  store.medilight.findings
    .filter((finding) => finding.reasonCode === 'DUPLICATE')
    .map((finding) => finding.ingredient),
)

const now = new Date()
// 브라우저의 현재 지역 날짜를 직접 조합해 요구된 한국어 형식을 하드코딩 없이 유지한다.
const currentDateIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
const currentDateLabel = `${now.getFullYear()}년 ${String(now.getMonth() + 1).padStart(2, '0')}월 ${String(now.getDate()).padStart(2, '0')}일`

onMounted(() => store.loadDashboard())
</script>

<template>
  <AppShell title="메인">
    <div v-if="store.loading" class="loading-card" role="status">
      <span class="spinner"></span>복약 정보를 불러오는 중입니다.
    </div>
    <div v-else-if="store.error" class="error-card" role="alert">
      <b>정보를 불러오지 못했습니다.</b><span>{{ store.error }}</span
      ><button class="button ghost" type="button" @click="store.loadDashboard">다시 시도</button>
    </div>
    <template v-else>
      <section class="hero-row">
        <div>
          <h2>{{ store.user?.name ?? '사용자' }} 님의 복약 현황</h2>
          <time class="hero-date" :datetime="currentDateIso">{{ currentDateLabel }}</time>
        </div>
        <div class="hero-actions">
          <RouterLink class="action-card emerald" to="/main/register-manual"
            ><span aria-hidden="true">＋</span><b>직접 약 입력</b></RouterLink
          >
          <RouterLink class="action-card mint" to="/main/symptom"
            ><span aria-hidden="true">✎</span><b>증상 기록</b></RouterLink
          >
        </div>
      </section>

      <section v-if="store.medications.length" class="content-section medilight-overview">
        <div class="section-title">
          <div><h2>Medi Light</h2></div>
        </div>
        <MediLightBanner :analysis="store.medilight" />
      </section>

      <section v-if="store.medications.length" class="content-section">
        <div class="section-title">
          <div><h2>복용 중인 항목</h2></div>
          <RouterLink to="/medications">복용 목록 →</RouterLink>
        </div>

        <div v-if="store.prescriptionMedications.length" class="medication-group">
          <header>
            <div><span class="group-dot emerald"></span><b>삼성내과의원 · 내과</b></div>
            <small>2026-08-14 처방 · 30일분</small>
          </header>
          <MedicationRow
            v-for="medication in store.prescriptionMedications"
            :key="medication.id"
            :medication="medication"
            :duplicated-ingredients="duplicatedIngredients"
          />
        </div>

        <div v-if="store.otherMedications.length" class="medication-group">
          <header>
            <div><span class="group-dot mint"></span><b>영양제 · 상비약</b></div>
            <small>직접 등록 {{ store.otherMedications.length }}건</small>
          </header>
          <MedicationRow
            v-for="medication in store.otherMedications"
            :key="medication.id"
            :medication="medication"
            :duplicated-ingredients="duplicatedIngredients"
          />
        </div>
      </section>

      <section v-else class="empty-state">
        <span class="empty-illustration" aria-hidden="true">＋</span>
        <b>아직 등록된 복용 항목이 없습니다</b>
        <p>등록 시 성분별 하루 총량과 중복 여부를 확인할 수 있습니다.</p>
        <div>
          <RouterLink class="button primary" to="/main/register-manual">등록하기</RouterLink>
        </div>
      </section>
    </template>
  </AppShell>
</template>
