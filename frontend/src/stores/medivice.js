// UC1·2·8~14·20·21·28의 화면 상태와 API 호출을 한곳에서 관리하는 Pinia 스토어.
// API 어댑터를 통해 실제 백엔드와 개발용 Mock을 동일한 화면 코드로 교체할 수 있게 한다.
import { computed, ref } from 'vue'
import { acceptHMRUpdate, defineStore } from 'pinia'
import {
  createMedication as apiCreateMedication,
  createSymptom as apiCreateSymptom,
  deleteMedication as apiDeleteMedication,
  extractMedicationOcr,
  getDashboard,
  getMedilight,
  login as apiLogin,
  requestReport,
  setStoredLoginId,
  signup as apiSignup,
} from '../api/adapter.js'

/** MedilightDto와 같은 모양의 "복용 항목 없음" 상태 — 초기값과 skipOnboarding()이 함께 쓴다. */
function emptyMedilight() {
  return {
    status: 'OK',
    summary: '현재 규칙에서 확인된 문제 없음',
    findings: [],
    totals: [],
    conflicts: [],
    ruleVersion: '',
    checkedAt: '',
    uncoveredCount: 0,
    noticeMessage: null,
  }
}

export const useMediviceStore = defineStore('medivice', () => {
  const user = ref(null)
  const medications = ref([])
  const symptoms = ref([])
  const medilight = ref(emptyMedilight())
  const language = ref('KO')
  const loading = ref(false)
  const error = ref('')
  const hydrated = ref(false)
  const reportStatus = ref('IDLE')
  const latestReport = ref(null)
  const ocrDrafts = ref([])
  const ocrLoading = ref(false)
  const ocrError = ref('')

  const prescriptionMedications = computed(() =>
    medications.value.filter((item) => item.type === 'PRESCRIPTION'),
  )
  const otherMedications = computed(() =>
    medications.value.filter((item) => item.type !== 'PRESCRIPTION'),
  )

  async function loadDashboard() {
    if (hydrated.value) return
    loading.value = true
    error.value = ''
    try {
      const response = await getDashboard()
      // 서버가 X-Medivice-User 헤더로 식별한 사용자를 그대로 신뢰한다 — 로컬 값으로 덮어쓰지 않는다.
      user.value = response.data.user
      medications.value = response.data.medications
      symptoms.value = response.data.symptoms
      medilight.value = response.data.medilight
      hydrated.value = true
    } catch (caughtError) {
      error.value =
        caughtError instanceof Error ? caughtError.message : '화면 데이터를 불러오지 못했습니다.'
    } finally {
      loading.value = false
    }
  }

  /** UC1 회원가입 — 이미 있는 아이디면 그 사용자를 그대로 이어서 쓴다(비밀번호는 검증하지 않음). */
  async function signup(payload) {
    loading.value = true
    error.value = ''
    try {
      const response = await apiSignup(payload)
      setStoredLoginId(response.data.username)
      user.value = response.data
      return response.data
    } catch (caughtError) {
      error.value = caughtError instanceof Error ? caughtError.message : '회원가입에 실패했습니다.'
      throw caughtError
    } finally {
      loading.value = false
    }
  }

  /** UC2 로그인 — 존재하지 않는 아이디면 실패한다(비밀번호는 검증하지 않음). */
  async function login(payload) {
    loading.value = true
    error.value = ''
    try {
      const response = await apiLogin(payload)
      setStoredLoginId(response.data.username)
      user.value = response.data
      hydrated.value = false // 다른 사용자로 다시 불러오도록 이전 캐시를 무효화한다
      await loadDashboard()
      return response.data
    } catch (caughtError) {
      error.value =
        caughtError instanceof Error
          ? caughtError.message
          : '로그인에 실패했습니다. 아이디를 확인해주세요.'
      throw caughtError
    } finally {
      loading.value = false
    }
  }

  /**
   * UC8~10(EXT-1) 사진 인식. 사진 한 장에 서로 다른 약이 여러 개 있을 수 있어(약봉투) 배열로
   * 온다. 결과는 저장하지 않고 확인 화면(SCR-REG-002)이 보여줄 초안으로만 담아 둔다 — 실제
   * 등록은 사용자가 확인한 뒤 createMedication()으로 항목마다 따로 호출한다(D-4).
   */
  async function runOcr(file) {
    ocrLoading.value = true
    ocrError.value = ''
    ocrDrafts.value = []
    try {
      const response = await extractMedicationOcr(file)
      ocrDrafts.value = response.data
      return response.data
    } catch (caughtError) {
      ocrError.value =
        caughtError instanceof Error ? caughtError.message : '이미지 인식에 실패했습니다.'
      throw caughtError
    } finally {
      ocrLoading.value = false
    }
  }

  /** UC13·UC8~12 등록 — 실제 POST /api/medications. 백엔드가 돌려준 medication·medilight를 그대로 반영한다. */
  async function createMedication(payload) {
    loading.value = true
    error.value = ''
    try {
      const response = await apiCreateMedication(payload)
      medications.value.push(response.data.medication)
      medilight.value = response.data.medilight
      return response.data.medication
    } catch (caughtError) {
      error.value = caughtError instanceof Error ? caughtError.message : '등록에 실패했습니다.'
      throw caughtError
    } finally {
      loading.value = false
    }
  }

  /** UC14 삭제 — 실제 DELETE /api/medications/:id. 삭제는 응답 본문이 없어 medilight는 별도로 다시 조회한다. */
  async function removeMedication(id) {
    loading.value = true
    error.value = ''
    try {
      await apiDeleteMedication(id)
      medications.value = medications.value.filter((item) => item.id !== id)
      const response = await getMedilight()
      medilight.value = response.data
    } catch (caughtError) {
      error.value = caughtError instanceof Error ? caughtError.message : '삭제에 실패했습니다.'
      throw caughtError
    } finally {
      loading.value = false
    }
  }

  /** UC20·21 증상 기록 — 실제 POST /api/symptoms. 복용 스냅샷은 백엔드가 저장 시점 목록으로 만든다. */
  async function addSymptom(entry) {
    loading.value = true
    error.value = ''
    try {
      const response = await apiCreateSymptom(entry)
      symptoms.value.unshift(response.data)
      return response.data
    } catch (caughtError) {
      error.value = caughtError instanceof Error ? caughtError.message : '증상 기록에 실패했습니다.'
      throw caughtError
    } finally {
      loading.value = false
    }
  }

  function skipOnboarding() {
    medications.value = []
    symptoms.value = []
    medilight.value = emptyMedilight()
    hydrated.value = true
  }

  /** 특이사항(지병·알레르기·키·몸무게)은 아직 저장 API가 없다(온보딩 화면 미연동, 문서상 알려진 gap).
   *  입력값을 로컬에 보관해 화면에 그대로 반영하는 것이 이번 범위의 전부다. */
  function updateProfile(profile) {
    user.value = { ...user.value, ...profile }
  }

  function setLanguage(nextLanguage) {
    language.value = nextLanguage
  }

  async function createReport(payload) {
    reportStatus.value = 'PENDING'
    error.value = ''
    try {
      reportStatus.value = 'PROCESSING'
      const response = await requestReport(payload)
      latestReport.value = response.data
      reportStatus.value = response.data.jobStatus
      return response.data
    } catch (caughtError) {
      reportStatus.value = 'FAILED'
      error.value =
        caughtError instanceof Error ? caughtError.message : '보고서를 만들지 못했습니다.'
      return null
    }
  }

  return {
    user,
    medications,
    symptoms,
    medilight,
    language,
    loading,
    error,
    hydrated,
    reportStatus,
    latestReport,
    prescriptionMedications,
    otherMedications,
    loadDashboard,
    signup,
    login,
    ocrDrafts,
    ocrLoading,
    ocrError,
    runOcr,
    createMedication,
    removeMedication,
    addSymptom,
    skipOnboarding,
    updateProfile,
    setLanguage,
    createReport,
  }
})

// 개발 서버에서 이 스토어 파일을 고칠 때마다 이미 마운트된 화면이 옛 클로저(signup/login이 없던
// 버전 등)를 계속 들고 있지 않도록 Pinia HMR을 명시적으로 붙인다. 이게 없으면 store.<새함수>가
// "not a function"으로 보이고, 브라우저를 새로고침해야만 없어진다.
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useMediviceStore, import.meta.hot))
}
