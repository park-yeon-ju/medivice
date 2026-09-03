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

onMounted(() => store.loadDashboard())
</script>

<template>
  <AppShell title="메인">
    <div v-if="store.loading" class="loading-card" role="status"><span class="spinner"></span>복약 정보를 불러오는 중입니다.</div>
    <div v-else-if="store.error" class="error-card" role="alert"><b>정보를 불러오지 못했습니다.</b><span>{{ store.error }}</span><button class="button ghost" type="button" @click="store.loadDashboard">다시 시도</button></div>
    <template v-else>
      <section class="hero-row">
        <div>
          <p class="eyebrow">2026년 9월 2일 수요일</p>
          <h2>{{ store.user?.name ?? '사용자' }} 님의 복약 현황</h2>
          <p>성분별 하루 총량과 기록을 한곳에서 확인하세요.</p>
        </div>
        <div class="hero-actions">
          <RouterLink class="action-card emerald" to="/main/register-manual"><span aria-hidden="true">＋</span><b>약 등록</b><small>직접 입력 · 사진 등록은 준비 중</small></RouterLink>
          <RouterLink class="action-card mint" to="/main/symptom"><span aria-hidden="true">✎</span><b>증상 기록</b><small>오늘의 몸 상태 남기기</small></RouterLink>
        </div>
      </section>

      <MediLightBanner v-if="store.medications.length" :analysis="store.medilight" />

      <section v-if="store.medications.length" class="content-section">
        <div class="section-title">
          <div><span class="section-kicker">ACTIVE MEDICATIONS</span><h2>복용 중인 항목</h2></div>
          <RouterLink to="/medications">전체 보기 →</RouterLink>
        </div>

        <div v-if="store.prescriptionMedications.length" class="medication-group">
          <header><div><span class="group-dot emerald"></span><b>삼성내과의원 · 내과</b></div><small>2026-08-14 처방 · 30일분</small></header>
          <MedicationRow
            v-for="medication in store.prescriptionMedications"
            :key="medication.id"
            :medication="medication"
            :duplicated-ingredients="duplicatedIngredients"
          />
        </div>

        <div v-if="store.otherMedications.length" class="medication-group">
          <header><div><span class="group-dot mint"></span><b>영양제 · 상비약</b></div><small>직접 등록 {{ store.otherMedications.length }}건</small></header>
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
        <p>하나만 등록해도 성분별 하루 총량과 중복 여부를 확인할 수 있습니다.</p>
        <div><RouterLink class="button primary" to="/main/register-manual">첫 항목 등록하기</RouterLink><RouterLink class="button ghost" to="/my/profile">특이사항부터 입력</RouterLink></div>
      </section>
    </template>
  </AppShell>
</template>
