<script setup>
import { computed, onMounted } from 'vue'
import AppShell from '@/layouts/AppShell.vue'
import MedicationRow from '@/components/MedicationRow.vue'
import { useMediviceStore } from '@/stores/medivice'

const store = useMediviceStore()
const duplicatedIngredients = computed(() =>
  store.medilight.findings.filter((item) => item.reasonCode === 'DUPLICATE').map((item) => item.ingredient),
)

onMounted(() => store.loadDashboard())

function removeMedication(id) {
  if (window.confirm('이 항목을 복용 목록에서 삭제할까요? 과거 증상 기록의 스냅샷은 유지됩니다.')) {
    store.removeMedication(id)
  }
}
</script>

<template>
  <AppShell title="복용 중인 약">
    <div class="page-intro row-intro"><div><p class="eyebrow">최종 수정 2026-09-02 21:40</p><h2>복용 중 {{ store.medications.length }}개</h2><p>처방약을 먼저, 영양제·상비약을 다음에 보여줍니다.</p></div><RouterLink class="button primary" to="/main/register-manual">약 등록</RouterLink></div>

    <section v-if="store.prescriptionMedications.length" class="medication-group expanded">
      <header><div><span class="group-dot emerald"></span><div><b>삼성내과의원 · 내과</b><small>고혈압 · 고지혈증</small></div></div><small>2026-08-14 처방 · 30일분</small></header>
      <MedicationRow v-for="medication in store.prescriptionMedications" :key="medication.id" :medication="medication" :duplicated-ingredients="duplicatedIngredients" show-actions @remove="removeMedication" />
    </section>

    <section v-if="store.otherMedications.length" class="medication-group expanded">
      <header><div><span class="group-dot mint"></span><b>영양제 · 상비약</b></div><small>직접 등록 {{ store.otherMedications.length }}건</small></header>
      <MedicationRow v-for="medication in store.otherMedications" :key="medication.id" :medication="medication" :duplicated-ingredients="duplicatedIngredients" show-actions @remove="removeMedication" />
    </section>

    <section v-if="!store.medications.length" class="empty-state"><b>복용 목록이 비어 있습니다</b><p>사진 또는 수기 입력으로 첫 항목을 등록해주세요.</p><RouterLink class="button primary" to="/main/register-manual">약 등록</RouterLink></section>
  </AppShell>
</template>
