<!--
  MedicationsView.vue
  현재 복용 중인 처방약·영양제·상비약을 유형별로 조회하고 수정·삭제 동선을 제공하는 화면.

  수정은 기존 항목을 편집 화면으로 전달하고, 삭제는 과거 증상 스냅샷을 유지한다는 사실을 확인받는다.
  관련 UC: UC13, UC14 / 화면: SCR-MAIN-003
-->
<script setup>
import { computed, onMounted } from 'vue'
import AppShell from '@/layouts/AppShell.vue'
import MedicationRow from '@/components/MedicationRow.vue'
import { useMediviceStore } from '@/stores/medivice'

const store = useMediviceStore()
const duplicatedIngredients = computed(() =>
  store.medilight.findings
    .filter((item) => item.reasonCode === 'DUPLICATE')
    .map((item) => item.ingredient),
)

onMounted(() => store.loadDashboard())

function removeMedication(id) {
  if (window.confirm('이 항목을 복용 목록에서 삭제할까요? 과거 증상 기록의 스냅샷은 유지됩니다.')) {
    store.removeMedication(id)
  }
}
</script>

<template>
  <AppShell title="복용 목록">
    <div class="page-intro row-intro">
      <div>
        <h2>복용 중 {{ store.medications.length }}개</h2>
      </div>
      <RouterLink class="button primary" to="/main/register-manual">직접 약 입력</RouterLink>
    </div>

    <section v-if="store.prescriptionMedications.length" class="medication-group expanded">
      <header>
        <div>
          <span class="group-dot emerald"></span>
          <div><b>삼성내과의원 · 내과</b><small>고혈압 · 고지혈증</small></div>
        </div>
        <small>2026-08-14 처방 · 30일분</small>
      </header>
      <MedicationRow
        v-for="medication in store.prescriptionMedications"
        :key="medication.id"
        :medication="medication"
        :duplicated-ingredients="duplicatedIngredients"
        show-actions
        @remove="removeMedication"
      />
    </section>

    <section v-if="store.otherMedications.length" class="medication-group expanded">
      <header>
        <div><span class="group-dot mint"></span><b>영양제 · 상비약</b></div>
        <small>직접 등록 {{ store.otherMedications.length }}건</small>
      </header>
      <MedicationRow
        v-for="medication in store.otherMedications"
        :key="medication.id"
        :medication="medication"
        :duplicated-ingredients="duplicatedIngredients"
        show-actions
        @remove="removeMedication"
      />
    </section>

    <section v-if="!store.medications.length" class="empty-state">
      <b>복용 목록이 비어 있습니다</b>
      <p>사진 또는 직접 입력으로 첫 항목을 등록해주세요.</p>
      <RouterLink class="button primary" to="/main/register-manual">직접 약 입력</RouterLink>
    </section>
  </AppShell>
</template>
