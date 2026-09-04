<!--
  MyView.vue
  사용자 특이사항·계정정보·가족 연동과 증상 기록 목록을 한곳에서 관리하는 화면.

  백엔드 저장 계약이 없는 항목은 현재 프론트 세션에서만 변경하고, 저장 결과와 입력 오류를 명확히 안내한다.
  관련 UC: UC22~UC25 / 화면: SCR-MY-001~SCR-MY-004
-->
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { COMMON_CONDITIONS, NO_CONDITION } from '@/healthOptions'
import AppShell from '@/layouts/AppShell.vue'
import { useMediviceStore } from '@/stores/medivice'

const props = defineProps({
  section: { type: String, default: 'overview' },
})

const router = useRouter()
const store = useMediviceStore()

const sectionMeta = {
  overview: { title: '마이페이지' },
  profile: { title: '특이사항 관리' },
  account: { title: '계정 관리' },
  symptoms: { title: '증상 기록' },
}

const profileEditing = ref(false)
const profileMessage = ref('')
const otherConditionOpen = ref(false)
const profileDraft = ref({
  conditions: [],
  otherCondition: '',
  allergies: '',
  height: '',
  weight: '',
  pregnancyStatus: '입력 안 함',
  adverseHistory: '',
  otherNotes: '',
})

const accountDraft = ref({ sex: '선택 안 함', birthDate: '', newPassword: '', passwordConfirm: '' })
const accountMessage = ref('')
const accountError = ref('')
const familyLoginId = ref('')
const familyModalOpen = ref(false)
const symptomFrom = ref('')
const symptomTo = ref('')

const dateRangeInvalid = computed(
  () => symptomFrom.value && symptomTo.value && symptomFrom.value > symptomTo.value,
)
const filteredSymptoms = computed(() => {
  if (dateRangeInvalid.value) return []
  return store.symptoms.filter((entry) => {
    const afterStart = !symptomFrom.value || entry.date >= symptomFrom.value
    const beforeEnd = !symptomTo.value || entry.date <= symptomTo.value
    return afterStart && beforeEnd
  })
})

function syncDrafts() {
  const user = store.user ?? {}
  const savedConditions = user.conditions ?? []
  const selectedConditions = savedConditions.filter((condition) =>
    COMMON_CONDITIONS.includes(condition),
  )
  const customConditions = savedConditions.filter(
    // 과거 상태에 '해당 없음'이 남아 있어도 자유 입력 진단명으로 표시하지 않는다.
    (condition) => !COMMON_CONDITIONS.includes(condition) && condition !== NO_CONDITION,
  )
  profileDraft.value = {
    // 기존 자유 입력값도 잃지 않도록 10개 선택지와 기타 입력으로 나눠 편집 초안에 담는다.
    conditions:
      selectedConditions.length || customConditions.length ? selectedConditions : [NO_CONDITION],
    otherCondition: customConditions.join(', '),
    allergies: (user.allergies ?? []).join(', '),
    height: user.height ?? '',
    weight: user.weight ?? '',
    pregnancyStatus: user.pregnancyStatus ?? '입력 안 함',
    adverseHistory: user.adverseHistory ?? '',
    otherNotes: user.otherNotes ?? '',
  }
  // 저장된 직접 입력 질환이 있으면 편집 진입 시 값이 숨겨지지 않도록 섹션을 함께 연다.
  otherConditionOpen.value = customConditions.length > 0
  accountDraft.value.sex = user.sex ?? '선택 안 함'
  accountDraft.value.birthDate = user.birthDate ?? ''
}

onMounted(async () => {
  await store.loadDashboard()
  syncDrafts()
})

watch(() => props.section, syncDrafts)

function logout() {
  router.push('/login')
}

function removeSymptom(id) {
  if (window.confirm('이 증상 기록을 삭제할까요?')) {
    store.symptoms = store.symptoms.filter((entry) => entry.id !== id)
  }
}

function startProfileEdit() {
  syncDrafts()
  profileMessage.value = ''
  profileEditing.value = true
}

function formatMeasurement(value, unit) {
  return value === null || value === undefined || value === '' ? '입력 안 함' : `${value} ${unit}`
}

function toggleProfileCondition(condition) {
  if (condition === NO_CONDITION) {
    // 해당 없음은 다른 질환과 모순되지 않도록 항상 단독 선택으로 관리한다.
    const selectingNoCondition = !profileDraft.value.conditions.includes(NO_CONDITION)
    profileDraft.value.conditions = selectingNoCondition ? [NO_CONDITION] : []
    if (selectingNoCondition) {
      profileDraft.value.otherCondition = ''
      otherConditionOpen.value = false
    }
    return
  }
  const selected = profileDraft.value.conditions.filter((item) => item !== NO_CONDITION)
  profileDraft.value.conditions = selected.includes(condition)
    ? selected.filter((item) => item !== condition)
    : [...selected, condition]
}

function toggleProfileOtherConditionSection() {
  otherConditionOpen.value = !otherConditionOpen.value
  if (otherConditionOpen.value) {
    // 직접 입력을 시작하면 '해당 없음'을 해제해 저장 가능한 상태가 한 가지 의미만 갖게 한다.
    profileDraft.value.conditions = profileDraft.value.conditions.filter(
      (condition) => condition !== NO_CONDITION,
    )
  }
  // 닫기는 표시 상태만 바꾸며 작성 중인 진단명은 사용자의 명시적 선택 없이 삭제하지 않는다.
}

function handleProfileOtherCondition() {
  if (profileDraft.value.otherCondition.trim()) {
    // 직접 입력 질환이 있으면 해당 없음 선택을 해제해 저장되는 건강 정보가 모순되지 않게 한다.
    profileDraft.value.conditions = profileDraft.value.conditions.filter(
      (condition) => condition !== NO_CONDITION,
    )
  }
}

function saveProfile() {
  const hasHeight = profileDraft.value.height !== ''
  const hasWeight = profileDraft.value.weight !== ''
  const height = hasHeight ? Number(profileDraft.value.height) : null
  const weight = hasWeight ? Number(profileDraft.value.weight) : null
  // 키·몸무게는 선택 정보이므로 비워 둔 저장은 허용하고, 입력한 값만 양수인지 확인한다.
  if (
    (hasHeight && (!Number.isFinite(height) || height <= 0)) ||
    (hasWeight && (!Number.isFinite(weight) || weight <= 0))
  ) {
    profileMessage.value = '키와 몸무게를 올바르게 입력해주세요.'
    return
  }
  const toList = (value) =>
    value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
  const selectedConditions = profileDraft.value.conditions.filter(
    (condition) => condition !== NO_CONDITION,
  )
  const customConditions = toList(profileDraft.value.otherCondition)
  store.updateProfile({
    conditions: [...new Set([...selectedConditions, ...customConditions])],
    allergies: toList(profileDraft.value.allergies),
    height,
    weight,
    pregnancyStatus: profileDraft.value.pregnancyStatus,
    adverseHistory: profileDraft.value.adverseHistory.trim(),
    otherNotes: profileDraft.value.otherNotes.trim(),
  })
  profileEditing.value = false
  profileMessage.value = '특이사항 변경 내용을 저장했습니다.'
}

function cancelProfileEdit() {
  syncDrafts()
  profileEditing.value = false
  profileMessage.value = ''
}

function saveAccount() {
  accountMessage.value = ''
  accountError.value = ''
  if (!accountDraft.value.birthDate) {
    accountError.value = '생년월일을 입력해주세요.'
    return
  }
  if (accountDraft.value.newPassword) {
    if (accountDraft.value.newPassword.length < 8) {
      accountError.value = '새 비밀번호는 8자 이상 입력해주세요.'
      return
    }
    if (accountDraft.value.newPassword !== accountDraft.value.passwordConfirm) {
      accountError.value = '새 비밀번호가 일치하지 않습니다.'
      return
    }
  }
  // 인증 API가 없는 현재 범위에서는 비밀번호 원문을 보관하지 않고 변경 완료 상태만 안내한다.
  store.updateAccount({ sex: accountDraft.value.sex, birthDate: accountDraft.value.birthDate })
  accountDraft.value.newPassword = ''
  accountDraft.value.passwordConfirm = ''
  accountMessage.value = '계정정보 변경 내용을 저장했습니다.'
}

function sendFamilyInvite() {
  if (!familyLoginId.value.trim()) return
  familyModalOpen.value = true
}

function clearDateRange() {
  symptomFrom.value = ''
  symptomTo.value = ''
}
</script>

<template>
  <AppShell :title="sectionMeta[props.section].title">
    <template v-if="section === 'overview'">
      <section class="profile-hero content-card">
        <span class="avatar">김</span>
        <div>
          <p class="eyebrow">MY PROFILE</p>
          <h2>{{ store.user?.name ?? '김민서' }}</h2>
          <p>
            {{ store.user?.sex ?? '여성' }} · {{ store.user?.birthDate ?? '1974-03-08' }} ({{
              store.user?.age ?? 52
            }}세) · {{ store.user?.username ?? 'minseo_k' }}
          </p>
          <div class="inline-chips">
            <span v-for="condition in store.user?.conditions" :key="condition" class="chip rx">{{
              condition
            }}</span
            ><span class="chip neutral">알레르기 {{ store.user?.allergies?.length ?? 0 }}건</span>
          </div>
        </div>
        <button class="button ghost" type="button" @click="logout">로그아웃</button>
      </section>
      <section class="tile-grid">
        <RouterLink to="/my/profile"
          ><span class="tile-icon">01</span><b>특이사항 관리</b>
          <p>복약 확인에 참고할 건강 정보를 한 번에 관리합니다.</p>
          <small>특이사항 관리 →</small></RouterLink
        >
        <RouterLink to="/my/account"
          ><span class="tile-icon">02</span><b>계정 관리</b>
          <p>비밀번호와 기본정보, 가족 계정 연동을 관리합니다.</p>
          <small>계정 관리 →</small></RouterLink
        >
        <RouterLink to="/my/symptoms"
          ><span class="tile-icon">03</span><b>증상 기록</b>
          <p>날짜 범위로 기록을 찾아보고 삭제할 수 있습니다.</p>
          <small>{{ store.symptoms.length }}건 →</small></RouterLink
        >
        <RouterLink to="/report/new"
          ><span class="tile-icon">04</span><b>메디 레포트</b>
          <p>기간과 언어를 골라 복약·증상 기록을 정리합니다.</p>
          <small>메디 레포트 →</small></RouterLink
        >
      </section>
      <section class="content-card language-card">
        <div>
          <b>표시 언어</b>
          <p>화면 언어를 바꾸고 성분명은 영문명으로 함께 표시합니다.</p>
        </div>
        <div class="language-switch large">
          <button
            type="button"
            :class="{ active: store.language === 'KO' }"
            @click="store.setLanguage('KO')"
          >
            한국어</button
          ><button
            type="button"
            :class="{ active: store.language === 'EN' }"
            @click="store.setLanguage('EN')"
          >
            English
          </button>
        </div>
      </section>
    </template>

    <section v-else-if="section === 'profile'" class="content-card">
      <div class="section-title">
        <div><h2>특이사항 관리</h2></div>
        <div class="section-actions">
          <template v-if="profileEditing">
            <button class="button ghost" type="button" @click="cancelProfileEdit">취소</button>
            <button class="button primary" type="button" @click="saveProfile">저장</button>
          </template>
          <button v-else class="button primary" type="button" @click="startProfileEdit">
            전체 수정
          </button>
        </div>
      </div>

      <div v-if="profileEditing" class="profile-edit-grid">
        <fieldset class="form-field profile-edit-wide">
          <legend>지병·진단 이력</legend>
          <div class="check-options">
            <button
              v-for="condition in [...COMMON_CONDITIONS, NO_CONDITION]"
              :key="condition"
              type="button"
              :class="{ selected: profileDraft.conditions.includes(condition) }"
              @click="toggleProfileCondition(condition)"
            >
              {{ condition }}
            </button>
            <button
              type="button"
              :class="{ selected: otherConditionOpen }"
              :aria-expanded="otherConditionOpen"
              aria-controls="profile-other-condition"
              @click="toggleProfileOtherConditionSection"
            >
              기타·직접 입력
            </button>
          </div>
          <label
            v-if="otherConditionOpen"
            id="profile-other-condition"
            class="form-field condition-direct-field"
          >
            <span>선택지에 없는 질환</span>
            <input
              v-model="profileDraft.otherCondition"
              placeholder="진단명을 쉼표로 구분해 입력"
              @input="handleProfileOtherCondition"
            />
          </label>
        </fieldset>
        <label class="form-field"
          ><span>알레르기·민감 반응</span
          ><input v-model="profileDraft.allergies" placeholder="쉼표로 구분해 입력"
        /></label>
        <label class="form-field"
          ><span>키</span>
          <div class="unit-input">
            <input v-model="profileDraft.height" type="number" min="1" /><i>cm</i>
          </div></label
        >
        <label class="form-field"
          ><span>몸무게</span>
          <div class="unit-input">
            <input v-model="profileDraft.weight" type="number" min="1" /><i>kg</i>
          </div></label
        >
        <label class="form-field"
          ><span>임신·수유 여부</span
          ><select v-model="profileDraft.pregnancyStatus">
            <option>입력 안 함</option>
            <option>해당 없음</option>
            <option>임신 중</option>
            <option>수유 중</option>
          </select></label
        >
        <label class="form-field profile-edit-wide"
          ><span>과거 약물 이상반응</span
          ><textarea
            v-model="profileDraft.adverseHistory"
            rows="3"
            placeholder="관찰한 증상과 당시 복용 정보를 입력하세요."
          ></textarea>
        </label>
        <label class="form-field profile-edit-wide"
          ><span>기타 특이사항</span
          ><textarea
            v-model="profileDraft.otherNotes"
            rows="3"
            placeholder="복약 확인 시 함께 참고할 내용을 입력하세요."
          ></textarea>
          <small>해당 없음을 선택한 경우에도 필요한 설명을 남길 수 있습니다.</small>
        </label>
      </div>

      <div v-else class="profile-table">
        <div class="profile-row">
          <span>지병·진단 이력</span>
          <div>
            <span v-for="condition in store.user?.conditions" :key="condition" class="chip rx">{{
              condition
            }}</span
            ><span v-if="!store.user?.conditions?.length">입력 안 함</span>
          </div>
          <small>복약 정보 확인 참고</small>
        </div>
        <div class="profile-row">
          <span>알레르기·민감 반응</span>
          <div>
            <span v-for="allergy in store.user?.allergies" :key="allergy" class="chip neutral">{{
              allergy
            }}</span
            ><span v-if="!store.user?.allergies?.length">입력 안 함</span>
          </div>
          <small>성분 확인 참고</small>
        </div>
        <div class="profile-row">
          <span>키 · 몸무게</span
          ><b
            >{{ formatMeasurement(store.user?.height, 'cm') }} ·
            {{ formatMeasurement(store.user?.weight, 'kg') }}</b
          ><small>복약 정보 확인 참고</small>
        </div>
        <div class="profile-row">
          <span>임신 · 수유 여부</span><b>{{ store.user?.pregnancyStatus ?? '입력 안 함' }}</b
          ><small>전문가 상담 시 참고</small>
        </div>
        <div class="profile-row">
          <span>과거 약물 이상반응</span><b>{{ store.user?.adverseHistory || '입력 안 함' }}</b
          ><small>관찰 기록 참고</small>
        </div>
        <div class="profile-row">
          <span>기타 특이사항</span><b>{{ store.user?.otherNotes || '입력 안 함' }}</b
          ><small>복약 정보 확인 참고</small>
        </div>
      </div>
      <p
        v-if="profileMessage"
        :class="profileEditing ? 'field-error' : 'success-message'"
        role="status"
      >
        {{ profileMessage }}
      </p>
    </section>

    <template v-else-if="section === 'account'">
      <form class="content-card account-card" @submit.prevent="saveAccount">
        <div class="section-title">
          <div><h2>계정 관리</h2></div>
          <button class="button primary" type="submit">계정정보 저장</button>
        </div>
        <div class="field-grid two account-fields">
          <label class="form-field"
            ><span>아이디</span><input :value="store.user?.username ?? 'minseo_k'" disabled /><small
              >사용자 식별값이므로 현재는 변경하지 않습니다.</small
            ></label
          >
          <label class="form-field"
            ><span>성별</span
            ><select v-model="accountDraft.sex">
              <option>여성</option>
              <option>남성</option>
              <option>선택 안 함</option>
            </select></label
          >
          <label class="form-field"
            ><span>생년월일</span><input v-model="accountDraft.birthDate" type="date" required
          /></label>
          <span></span>
          <label class="form-field"
            ><span>새 비밀번호</span
            ><input
              v-model="accountDraft.newPassword"
              type="password"
              autocomplete="new-password"
              placeholder="변경할 경우 8자 이상 입력"
          /></label>
          <label class="form-field"
            ><span>새 비밀번호 확인</span
            ><input
              v-model="accountDraft.passwordConfirm"
              type="password"
              autocomplete="new-password"
              placeholder="새 비밀번호를 한 번 더 입력"
          /></label>
        </div>
        <p v-if="accountError" class="field-error" role="alert">{{ accountError }}</p>
        <p v-if="accountMessage" class="success-message" role="status">{{ accountMessage }}</p>
      </form>
      <section class="content-card family-card">
        <div>
          <h2>가족 계정 연동</h2>
          <p>상대방이 수락하기 전에는 어떤 기록도 공유되지 않습니다.</p>
        </div>
        <div class="input-action">
          <input
            v-model="familyLoginId"
            placeholder="연동할 가족의 아이디"
            @keyup.enter="sendFamilyInvite"
          /><button
            class="button ghost"
            type="button"
            :disabled="!familyLoginId.trim()"
            @click="sendFamilyInvite"
          >
            연동 신청
          </button>
        </div>
      </section>
      <section class="dangerless-card">
        <div>
          <b>로그아웃</b>
          <p>공용 기기에서는 현재 기기의 세션을 종료해주세요.</p>
        </div>
        <button class="button ghost" type="button" @click="logout">로그아웃</button>
      </section>

      <div
        v-if="familyModalOpen"
        class="modal-backdrop"
        role="presentation"
        @click.self="familyModalOpen = false"
      >
        <section
          class="confirmation-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="family-modal-title"
        >
          <h2 id="family-modal-title">가족 연동 신청</h2>
          <p>{{ familyLoginId.trim() }}님께 가족 연동 신청을 보냈습니다.</p>
          <button class="button primary" type="button" @click="familyModalOpen = false">
            확인
          </button>
        </section>
      </div>
    </template>

    <template v-else>
      <div class="page-intro row-intro">
        <div>
          <h2>증상 기록 · {{ filteredSymptoms.length }}건</h2>
        </div>
        <RouterLink class="button primary" to="/main/symptom">증상 기록</RouterLink>
      </div>
      <section class="content-card date-filter-card" aria-label="기록 날짜 검색">
        <label class="form-field"
          ><span>시작일</span><input v-model="symptomFrom" type="date"
        /></label>
        <label class="form-field"
          ><span>종료일</span><input v-model="symptomTo" type="date"
        /></label>
        <button class="button ghost" type="button" @click="clearDateRange">날짜 초기화</button>
        <p v-if="dateRangeInvalid" class="field-error">종료일은 시작일보다 빠를 수 없습니다.</p>
      </section>
      <section class="symptom-list">
        <article v-for="entry in filteredSymptoms" :key="entry.id" class="symptom-log">
          <time :datetime="`${entry.date}T${entry.time}`"
            ><b>{{ entry.date.slice(5).replace('-', '.') }}</b
            ><span>{{ entry.time }}</span></time
          >
          <div>
            <div class="inline-chips">
              <span v-for="symptom in entry.symptoms" :key="symptom" class="chip symptom">{{
                symptom
              }}</span>
            </div>
            <p>{{ entry.note }}</p>
            <small v-if="entry.medicationSnapshot.length"
              >복용 스냅샷 {{ entry.medicationSnapshot.length }}건 ·
              {{
                entry.medicationSnapshot
                  .map((item) => item.name)
                  .slice(0, 4)
                  .join(' · ')
              }}{{
                entry.medicationSnapshot.length > 4
                  ? ` 외 ${entry.medicationSnapshot.length - 4}`
                  : ''
              }}</small
            ><small v-else>연결된 복용 항목 없음 · 해당 날짜에 등록된 항목이 없습니다.</small>
          </div>
          <div class="row-actions">
            <button class="text-button" type="button" @click="removeSymptom(entry.id)">삭제</button>
          </div>
        </article>
        <div v-if="!filteredSymptoms.length && !dateRangeInvalid" class="empty-state compact-empty">
          <b>선택한 기간의 기록이 없습니다.</b>
        </div>
      </section>
    </template>
  </AppShell>
</template>
