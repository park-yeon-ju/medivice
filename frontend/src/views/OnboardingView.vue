<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BrandLockup from '@/components/BrandLockup.vue'
import MedicationRow from '@/components/MedicationRow.vue'
import { useMediviceStore } from '@/stores/medivice'

const props = defineProps({
  step: { type: String, default: 'choice' },
})

const router = useRouter()
const store = useMediviceStore()
const conditions = ref(['고혈압'])
const allergies = ref('아스피린 · 페니실린계')
const height = ref(162)
const weight = ref(58)
const pregnancy = ref('선택 안 함')
const adverseHistory = ref('')

const stepLabel = computed(() => {
  if (props.step === 'profile') return 'STEP 1 / 2 · SCR-ONB-002'
  if (props.step === 'medications') return 'STEP 2 / 2 · SCR-ONB-003'
  return '첫 로그인 1회 · SCR-ONB-001'
})

onMounted(() => {
  if (props.step === 'medications') store.loadDashboard()
})

function toggleCondition(condition) {
  conditions.value = conditions.value.includes(condition)
    ? conditions.value.filter((item) => item !== condition)
    : [...conditions.value, condition]
}

function saveProfile() {
  store.updateProfile({
    conditions: conditions.value,
    allergies: allergies.value.split('·').map((item) => item.trim()).filter(Boolean),
    height: height.value,
    weight: weight.value,
    adverseHistory: adverseHistory.value,
  })
  router.push('/onboarding/medications')
}

function skipAll() {
  store.skipOnboarding()
  router.push('/main')
}
</script>

<template>
  <main class="onboarding-stage">
    <header class="onboarding-header">
      <BrandLockup compact />
      <span>{{ stepLabel }}</span>
    </header>

    <section v-if="step === 'choice'" class="onboarding-card choice-card">
      <span class="question-icon" aria-hidden="true">?</span>
      <p class="eyebrow">UC3 · 문진 진행 여부</p>
      <h1>지금 복용 중인 약을 등록할까요?</h1>
      <p class="lead-copy">알레르기·지병과 복용 중인 항목을 등록하면 성분 중복과 하루 총량을 바로 확인할 수 있습니다. 약 3분 정도 걸립니다.</p>
      <div class="choice-grid">
        <button class="choice-option selected" type="button" @click="router.push('/onboarding/profile')">
          <b>지금 등록할게요</b><span>특이사항 → 복용 항목 순서로 진행</span>
        </button>
        <button class="choice-option" type="button" @click="skipAll">
          <b>나중에 할게요</b><span>빈 화면으로 시작하고 언제든 추가</span>
        </button>
      </div>
    </section>

    <form v-else-if="step === 'profile'" class="onboarding-card" @submit.prevent="saveProfile">
      <div class="section-heading">
        <div><p class="eyebrow">UC4 · 판정에 필요한 정보만</p><h1>특이사항을 알려주세요</h1></div>
        <span class="progress-label">1 / 2</span>
      </div>
      <fieldset class="form-field">
        <legend>지병 <b aria-hidden="true">*</b></legend>
        <div class="check-options">
          <button
            v-for="condition in ['고혈압', '당뇨', '고지혈증', '갑상선 질환', '신장 질환', '없음']"
            :key="condition"
            type="button"
            :class="{ selected: conditions.includes(condition) }"
            @click="toggleCondition(condition)"
          >{{ condition }}</button>
        </div>
      </fieldset>
      <label class="form-field">
        <span>알레르기</span>
        <input v-model="allergies" />
        <small>성분 코드로 저장해 새 약 등록 시 대조할 수 있도록 설계했습니다.</small>
      </label>
      <div class="field-grid two">
        <label class="form-field"><span>키</span><div class="unit-input"><input v-model="height" type="number" /><i>cm</i></div></label>
        <label class="form-field"><span>몸무게</span><div class="unit-input"><input v-model="weight" type="number" /><i>kg</i></div></label>
      </div>
      <div class="optional-block">
        <div><b>선택 입력</b><span>필요성과 활용 목적을 확인한 뒤 입력하세요.</span></div>
        <div class="field-grid two">
          <label class="form-field"><span>임신·수유 여부</span><select v-model="pregnancy"><option>선택 안 함</option><option>해당 없음</option><option>임신 중</option><option>수유 중</option></select></label>
          <label class="form-field"><span>과거 약물 이상반응</span><input v-model="adverseHistory" placeholder="예: 특정 성분 복용 후 두드러기" /></label>
        </div>
      </div>
      <div class="form-actions split"><button class="button text" type="button" @click="router.push('/onboarding/medications')">건너뛰기</button><button class="button primary" type="submit">다음 · 복용 항목 등록</button></div>
    </form>

    <section v-else class="onboarding-card medication-onboarding">
      <div class="section-heading">
        <div><p class="eyebrow">UC5 · 초기 복용 항목</p><h1>지금 복용 중인 항목을 담아주세요</h1></div>
        <span class="progress-label">2 / 2</span>
      </div>
      <div class="quick-actions three">
        <RouterLink class="button secondary" to="/main/register">사진으로 등록</RouterLink>
        <RouterLink class="button secondary" to="/main/register">처방전 등록</RouterLink>
        <RouterLink class="button ghost" to="/main/register-manual">직접 입력</RouterLink>
      </div>
      <div class="medication-group">
        <header><div><b>담은 항목</b><span>{{ store.medications.length }}개</span></div></header>
        <MedicationRow v-for="medication in store.medications.slice(0, 3)" :key="medication.id" :medication="medication" />
      </div>
      <div class="form-actions split"><button class="button text" type="button" @click="skipAll">건너뛰기</button><button class="button primary" type="button" @click="router.push('/main')">등록 완료하고 메인으로</button></div>
    </section>
  </main>
</template>
