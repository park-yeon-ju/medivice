<!--
  OnboardingView.vue
  회원가입 직후 특이사항과 초기 복용 항목을 순서대로 확인하는 설문·문진 화면.

  사용자는 문진을 진행하거나 건너뛸 수 있으며, 입력값은 프론트 상태에 반영해 이후 화면에서 재사용한다.
  관련 UC: UC3~UC5 / 화면: SCR-ONB-001, SCR-ONB-002, SCR-ONB-003
-->
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BrandLockup from '@/components/BrandLockup.vue'
import MedicationRow from '@/components/MedicationRow.vue'
import { COMMON_CONDITIONS, NO_CONDITION } from '@/healthOptions'
import { useMediviceStore } from '@/stores/medivice'

const props = defineProps({
  step: { type: String, default: 'choice' },
})

const router = useRouter()
const store = useMediviceStore()
const conditions = ref([])
const otherCondition = ref('')
const otherConditionOpen = ref(false)
const allergies = ref('아스피린 · 페니실린계')
const height = ref(162)
const weight = ref(58)
const pregnancy = ref('선택 안 함')
const adverseHistory = ref('')
const otherNotes = ref('')
const profileError = ref('')

const stepLabel = computed(() => {
  if (props.step === 'profile') return '문진 1 / 2'
  if (props.step === 'medications') return '문진 2 / 2'
  return ''
})

onMounted(() => {
  if (props.step === 'medications') store.loadDashboard()
})

function toggleCondition(condition) {
  if (condition === NO_CONDITION) {
    // '해당 없음'은 다른 진단과 동시에 성립하지 않으므로 단독 선택으로 전환한다.
    const selectingNoCondition = !conditions.value.includes(NO_CONDITION)
    conditions.value = selectingNoCondition ? [NO_CONDITION] : []
    if (selectingNoCondition) {
      otherCondition.value = ''
      otherConditionOpen.value = false
    }
    return
  }
  const selected = conditions.value.filter((item) => item !== NO_CONDITION)
  conditions.value = selected.includes(condition)
    ? selected.filter((item) => item !== condition)
    : [...selected, condition]
}

function toggleOtherConditionSection() {
  otherConditionOpen.value = !otherConditionOpen.value
  if (otherConditionOpen.value) {
    // 직접 입력을 시작하는 순간 '해당 없음'을 해제해 서로 모순되는 건강 정보가 남지 않게 한다.
    conditions.value = conditions.value.filter((condition) => condition !== NO_CONDITION)
  }
  // 입력값이 있는 상태로 닫더라도 다시 열어 이어 쓸 수 있도록 값은 지우지 않는다.
}

function handleOtherConditionInput() {
  if (otherCondition.value.trim()) {
    // 직접 입력한 진단이 생기면 '해당 없음' 선택을 해제해 서로 모순되는 상태를 막는다.
    conditions.value = conditions.value.filter((condition) => condition !== NO_CONDITION)
  }
}

function saveProfile() {
  profileError.value = ''
  if (!conditions.value.length && !otherCondition.value.trim()) {
    profileError.value = '해당하는 질환이나 해당 없음을 선택해주세요.'
    return
  }
  const selectedConditions = conditions.value.filter((condition) => condition !== NO_CONDITION)
  const customConditions = otherCondition.value
    .split(',')
    .map((condition) => condition.trim())
    .filter(Boolean)
  store.updateProfile({
    // 10개 선택지 밖의 진단도 같은 목록에 보존해 이후 복약 화면에서 함께 참고한다.
    conditions: [...new Set([...selectedConditions, ...customConditions])],
    allergies: allergies.value
      .split('·')
      .map((item) => item.trim())
      .filter(Boolean),
    height: height.value,
    weight: weight.value,
    pregnancyStatus: pregnancy.value,
    adverseHistory: adverseHistory.value,
    otherNotes: otherNotes.value.trim(),
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
    <header class="onboarding-header" :class="{ 'choice-header': step === 'choice' }">
      <BrandLockup :show-tagline="false" />
      <span v-if="step !== 'choice'">{{ stepLabel }}</span>
    </header>

    <section v-if="step === 'choice'" class="onboarding-card choice-card">
      <span class="question-icon" aria-hidden="true">?</span>
      <h1>설문·문진을 시작할까요?</h1>
      <p class="onboarding-step-copy">첫 로그인 설문</p>
      <p class="lead-copy">특이사항과 복용 중인 항목을 순서대로 확인합니다.</p>
      <div class="choice-grid">
        <button
          class="choice-option selected"
          type="button"
          @click="router.push('/onboarding/profile')"
        >
          <b>설문·문진 시작</b><span>특이사항과 복용 항목을 입력해요.</span>
        </button>
        <button class="choice-option" type="button" @click="skipAll">
          <b>나중에 입력</b><span>메인 화면에서 다시 입력할 수 있어요</span>
        </button>
      </div>
    </section>

    <form v-else-if="step === 'profile'" class="onboarding-card" @submit.prevent="saveProfile">
      <div class="section-heading">
        <div><h1>특이사항 문진</h1></div>
        <span class="progress-label">1 / 2</span>
      </div>
      <fieldset class="form-field">
        <legend>지병 <b aria-hidden="true">*</b></legend>
        <div class="check-options">
          <button
            v-for="condition in [...COMMON_CONDITIONS, NO_CONDITION]"
            :key="condition"
            type="button"
            :class="{ selected: conditions.includes(condition) }"
            @click="toggleCondition(condition)"
          >
            {{ condition }}
          </button>
          <button
            type="button"
            :class="{ selected: otherConditionOpen }"
            :aria-expanded="otherConditionOpen"
            aria-controls="onboarding-other-condition"
            @click="toggleOtherConditionSection"
          >
            기타·직접 입력
          </button>
        </div>
        <label
          v-if="otherConditionOpen"
          id="onboarding-other-condition"
          class="form-field condition-direct-field"
        >
          <span>선택지에 없는 질환</span>
          <input
            v-model="otherCondition"
            placeholder="진단명을 쉼표로 구분해 입력하세요"
            @input="handleOtherConditionInput"
          /><small>10개 선택지에 없는 진단명을 직접 입력할 수 있습니다.</small>
        </label>
      </fieldset>
      <label class="form-field">
        <span>알레르기</span>
        <input v-model="allergies" />
      </label>
      <div class="field-grid two">
        <label class="form-field"
          ><span>키</span>
          <div class="unit-input"><input v-model="height" type="number" /><i>cm</i></div></label
        >
        <label class="form-field"
          ><span>몸무게</span>
          <div class="unit-input"><input v-model="weight" type="number" /><i>kg</i></div></label
        >
      </div>
      <div class="optional-block">
        <div><b>선택 입력</b><span>필요성과 활용 목적을 확인한 뒤 입력하세요.</span></div>
        <div class="field-grid two">
          <label class="form-field"
            ><span>임신·수유 여부</span
            ><select v-model="pregnancy">
              <option>선택 안 함</option>
              <option>해당 없음</option>
              <option>임신 중</option>
              <option>수유 중</option>
            </select></label
          >
          <label class="form-field"
            ><span>과거 약물 이상반응</span
            ><input v-model="adverseHistory" placeholder="예: 특정 성분 복용 후 두드러기"
          /></label>
          <label class="form-field profile-edit-wide"
            ><span>기타 특이사항</span
            ><textarea
              v-model="otherNotes"
              rows="3"
              placeholder="복약 확인 시 함께 참고할 내용을 입력하세요."
            ></textarea
            ><small>해당 없음을 선택한 경우에도 필요한 설명을 남길 수 있습니다.</small></label
          >
        </div>
      </div>
      <p v-if="profileError" class="field-error" role="alert">{{ profileError }}</p>
      <div class="form-actions split">
        <button class="button text" type="button" @click="router.push('/onboarding/medications')">
          건너뛰기</button
        ><button class="button primary" type="submit">다음 · 복용 항목 등록</button>
      </div>
    </form>

    <section v-else class="onboarding-card medication-onboarding">
      <div class="section-heading">
        <div><h1>복용 항목 등록</h1></div>
        <span class="progress-label">2 / 2</span>
      </div>
      <div class="quick-actions two">
        <RouterLink class="button secondary" to="/main/register">사진으로 약 등록</RouterLink>
        <RouterLink class="button ghost" to="/main/register-manual">직접 약 입력</RouterLink>
      </div>
      <div class="medication-group">
        <header>
          <div>
            <b>담은 항목</b><span>{{ store.medications.length }}개</span>
          </div>
        </header>
        <MedicationRow
          v-for="medication in store.medications.slice(0, 3)"
          :key="medication.id"
          :medication="medication"
        />
      </div>
      <div class="form-actions split">
        <button class="button text" type="button" @click="skipAll">건너뛰기</button
        ><button class="button primary" type="button" @click="router.push('/main')">
          등록 완료하고 메인으로
        </button>
      </div>
    </section>
  </main>
</template>
