// 백엔드 없이 UC1·2·8~14·20·21·28 화면 흐름을 검증하기 위한 개발 전용 Mock API.
// 실제 client.js와 같은 함수명·응답 구조·성공 Status Code를 유지해 UI가 API 구현을 구분하지 않게 한다.
const wait = (milliseconds = 320) =>
  new Promise((resolve) => window.setTimeout(resolve, milliseconds))

const clone = (value) => JSON.parse(JSON.stringify(value))

export const API_CONTRACT = Object.freeze({
  signup: { method: 'POST', endpoint: '/api/auth/signup', successStatus: 201 },
  login: { method: 'POST', endpoint: '/api/auth/login', successStatus: 200 },
  dashboard: { method: 'GET', endpoint: '/api/dashboard', successStatus: 200 },
  medilight: { method: 'GET', endpoint: '/api/medilight', successStatus: 200 },
  medicationCreate: { method: 'POST', endpoint: '/api/medications', successStatus: 201 },
  medicationDelete: { method: 'DELETE', endpoint: '/api/medications/:id', successStatus: 204 },
  medicationOcr: { method: 'POST', endpoint: '/api/medications/ocr', successStatus: 200 },
  symptomCreate: { method: 'POST', endpoint: '/api/symptoms', successStatus: 201 },
  reportCreate: { method: 'POST', endpoint: '/api/reports', successStatus: 202 },
})

const defaultUser = {
  id: 1,
  username: 'minseo_k',
  name: '김민서',
  sex: '여성',
  birthDate: '1974-03-08',
  age: 52,
  conditions: ['고혈압'],
  allergies: ['아스피린', '페니실린계'],
  height: 162,
  weight: 58,
  adverseHistory: '2018년 아스피린 복용 후 두드러기',
}

// 회원가입과 로그인 후 대시보드가 같은 사용자를 반환해야 하므로 Mock 세션도 메모리에서 유지한다.
const users = new Map([[defaultUser.username, clone(defaultUser)]])
let activeUser = clone(defaultUser)
let medicationSequence = 1
let symptomSequence = 1

const medications = [
  {
    id: 'rx-amozartan',
    type: 'PRESCRIPTION',
    name: '아모잘탄정 5/50mg',
    ingredients: [
      { name: '암로디핀', englishName: 'Amlodipine', amount: 5, unit: 'mg' },
      { name: '로사르탄칼륨', englishName: 'Losartan K', amount: 50, unit: 'mg' },
    ],
    dose: 1,
    doseUnit: '정',
    timesPerDay: 1,
    timing: '아침',
    hospital: '삼성내과의원',
    department: '내과',
    reason: '고혈압',
    startDate: '2026-08-14',
    duration: '30일분',
    analysisEligible: false,
    aiExplanation:
      '혈압 관리에 사용하는 두 성분이 한 알에 들어 있는 처방약입니다. 복용법은 처방 내용을 우선해 확인하세요.',
  },
  {
    id: 'rx-crestor',
    type: 'PRESCRIPTION',
    name: '크레스토정 5mg',
    ingredients: [{ name: '로수바스타틴', englishName: 'Rosuvastatin', amount: 5, unit: 'mg' }],
    dose: 1,
    doseUnit: '정',
    timesPerDay: 1,
    timing: '저녁',
    hospital: '삼성내과의원',
    department: '내과',
    reason: '고지혈증',
    startDate: '2026-08-14',
    duration: '30일분',
    analysisEligible: false,
    aiExplanation:
      '혈중 콜레스테롤 관리에 일반적으로 사용하는 성분입니다. 이상 증상이 지속되면 의료진에게 알리세요.',
  },
  {
    id: 'sup-vitamin-d',
    type: 'SUPPLEMENT',
    name: '비타민D 2000IU',
    ingredients: [{ name: '비타민D', englishName: 'Cholecalciferol', amount: 2000, unit: 'IU' }],
    dose: 1,
    doseUnit: '정',
    timesPerDay: 1,
    reason: '뼈 건강',
    startDate: '2026-06-02',
    analysisEligible: true,
  },
  {
    id: 'sup-centrum',
    type: 'SUPPLEMENT',
    name: '센트룸 실버',
    ingredients: [
      { name: '비타민D', englishName: 'Vitamin D', amount: 400, unit: 'IU' },
      { name: '비타민C', englishName: 'Vitamin C', amount: 60, unit: 'mg' },
    ],
    ingredientNote: '비타민D 400 IU · 비타민C 60mg 외 21종',
    dose: 1,
    doseUnit: '정',
    timesPerDay: 1,
    reason: '전반 보충',
    startDate: '2026-05-11',
    analysisEligible: true,
  },
  {
    id: 'sup-omega3',
    type: 'SUPPLEMENT',
    name: '오메가3 1000mg',
    ingredients: [
      { name: 'EPA', englishName: 'EPA', amount: 180, unit: 'mg' },
      { name: 'DHA', englishName: 'DHA', amount: 120, unit: 'mg' },
    ],
    dose: 2,
    doseUnit: '캡슐',
    timesPerDay: 1,
    reason: '혈행 개선',
    startDate: '2026-05-11',
    analysisEligible: true,
  },
  {
    id: 'otc-tylenol',
    type: 'OTC',
    name: '타이레놀 500mg',
    ingredients: [
      { name: '아세트아미노펜', englishName: 'Acetaminophen', amount: 500, unit: 'mg' },
    ],
    dose: 1,
    doseUnit: '정',
    timesPerDay: null,
    maxTimesPerDay: 3,
    timing: '필요 시',
    reason: '두통',
    startDate: '상시',
    analysisEligible: false,
  },
]

const symptoms = [
  {
    id: 'symptom-0902',
    date: '2026-09-02',
    time: '21:30',
    symptoms: ['어지러움', '속쓰림'],
    note: '아침 약을 복용한 뒤 어지러웠음. 전날 잠을 4시간밖에 못 잤음.',
    medicationSnapshot: medications.map(({ id, name }) => ({ id, name })),
  },
  {
    id: 'symptom-0828',
    date: '2026-08-28',
    time: '09:10',
    symptoms: ['두통'],
    note: '오전에 관자놀이 쪽이 욱신거림. 타이레놀 1정을 복용한 뒤 나아짐.',
    medicationSnapshot: medications.map(({ id, name }) => ({ id, name })),
  },
  {
    id: 'symptom-0821',
    date: '2026-08-21',
    time: '22:05',
    symptoms: ['피로감'],
    note: '하루 종일 몸이 무거웠고 특별한 다른 증상은 없었음.',
    medicationSnapshot: [],
  },
]

export const ingredientRules = Object.freeze({
  비타민D: { upperLimit: 4000, unit: 'IU', reference: '2020 한국인 영양소 섭취기준' },
  비타민C: { upperLimit: 2000, unit: 'mg', reference: '2020 한국인 영양소 섭취기준' },
  EPA: { upperLimit: 2000, unit: 'mg', reference: '화면 시연용 샘플 규칙' },
  DHA: { upperLimit: 2000, unit: 'mg', reference: '화면 시연용 샘플 규칙' },
})

export function analyzeMedications(items) {
  const totalsByIngredient = new Map()

  items
    .filter((item) => item.analysisEligible && item.timesPerDay)
    .forEach((item) => {
      item.ingredients.forEach((ingredient) => {
        const amount = ingredient.amount * item.dose * item.timesPerDay
        const current = totalsByIngredient.get(ingredient.name) ?? {
          ingredient: ingredient.name,
          unit: ingredient.unit,
          dailyTotal: 0,
          sources: [],
        }
        current.dailyTotal += amount
        current.sources.push({
          product: item.name,
          amount,
          dose: item.dose,
          timesPerDay: item.timesPerDay,
        })
        totalsByIngredient.set(ingredient.name, current)
      })
    })

  const totals = [...totalsByIngredient.values()].map((total) => {
    const rule = ingredientRules[total.ingredient]
    const ratio = rule ? total.dailyTotal / rule.upperLimit : null
    const isDuplicate = total.sources.length > 1
    let status = 'OK'
    let reasonCode = null
    if (rule && total.dailyTotal > rule.upperLimit) {
      status = 'CRIT'
      reasonCode = 'OVER_LIMIT'
    } else if (isDuplicate) {
      status = 'WARN'
      reasonCode = 'DUPLICATE'
    } else if (ratio !== null && ratio >= 0.8) {
      status = 'WARN'
      reasonCode = 'NEAR_LIMIT'
    }
    return {
      ...total,
      status,
      reasonCode,
      upperLimit: rule?.upperLimit ?? null,
      reference: rule?.reference ?? null,
      ratio,
    }
  })

  const findings = totals.filter((total) => total.status !== 'OK')
  const uncoveredCount = totals.filter((total) => !total.upperLimit).length
  const status = findings.some((finding) => finding.status === 'CRIT')
    ? 'CRIT'
    : findings.length
      ? 'WARN'
      : 'OK'

  return {
    status,
    summary:
      status === 'CRIT'
        ? `전문가 확인이 필요한 항목 ${findings.length}건`
        : status === 'WARN'
          ? `확인이 필요한 항목 ${findings.length}건`
          : '현재 규칙에서 확인된 문제 없음',
    findings,
    totals,
    conflicts: [],
    ruleVersion: 'v0.3-demo',
    checkedAt: '2026-09-03',
    uncoveredCount,
    noticeMessage: uncoveredCount
      ? `현재 Mock 규칙에 기준이 없는 성분 ${uncoveredCount}종은 판정에서 제외되었습니다.`
      : null,
  }
}

function requireText(value, message) {
  const normalized = typeof value === 'string' ? value.trim() : ''
  if (!normalized) throw new Error(message)
  return normalized
}

function createUser(payload) {
  const loginId = requireText(payload?.loginId, '아이디를 입력해주세요.')
  requireText(payload?.password, '비밀번호를 입력해주세요.')
  const birthDate = payload.birthDate || null
  const birthYear = birthDate ? Number(birthDate.slice(0, 4)) : null

  return {
    id: users.size + 1,
    username: loginId,
    name: loginId,
    sex: payload.sex || '선택 안 함',
    birthDate,
    age: birthYear ? new Date().getFullYear() - birthYear : null,
    conditions: [],
    allergies: [],
    height: null,
    weight: null,
    adverseHistory: '',
  }
}

/** UC1. 실제 API처럼 기존 아이디는 재사용하고, 새 아이디는 Mock 메모리에 등록한다. */
export async function signup(payload) {
  await wait()
  const candidate = createUser(payload)
  const storedUser = users.get(candidate.username) ?? candidate
  users.set(storedUser.username, clone(storedUser))
  activeUser = clone(storedUser)
  return { status: 201, data: clone(activeUser) }
}

/** UC2. 기본 Mock 계정은 minseo_k이며 비밀번호는 Sprint 1 계약처럼 존재 여부만 확인한다. */
export async function login(payload) {
  await wait()
  const loginId = requireText(payload?.loginId, '아이디를 입력해주세요.')
  requireText(payload?.password, '비밀번호를 입력해주세요.')
  const storedUser = users.get(loginId)
  if (!storedUser) throw new Error(`존재하지 않는 아이디입니다: ${loginId}`)
  activeUser = clone(storedUser)
  return { status: 200, data: clone(activeUser) }
}

export async function getDashboard() {
  await wait()
  return {
    status: 200,
    data: clone({
      user: activeUser,
      medications,
      symptoms,
      medilight: analyzeMedications(medications),
    }),
  }
}

export async function getMedilight() {
  await wait()
  return { status: 200, data: clone(analyzeMedications(medications)) }
}

/** UC8~10. 실제 사진을 전송하지 않고, 사용자가 확인·수정할 수 있는 구조화된 초안 배열을 반환한다. */
export async function extractMedicationOcr(file) {
  await wait(650)
  if (!file) throw new Error('사진을 먼저 선택해주세요.')

  return {
    status: 200,
    data: clone([
      {
        type: 'PRESCRIPTION',
        name: '케이캡정 50mg',
        ingredients: [{ name: '테고프라잔', amount: 50, unit: 'mg' }],
        dose: 1,
        timesPerDay: 1,
        hospital: '서울메디내과의원',
        department: '내과',
        duration: '14일분',
        rows: [
          { key: '파일', value: file.name, confidence: 1 },
          { key: '제품명', value: '케이캡정 50mg', confidence: 0.91 },
          { key: '성분', value: '테고프라잔 50mg', confidence: 0.87 },
        ],
        note: 'Mock OCR 결과입니다. 등록 전 제품명과 성분을 직접 확인해주세요.',
      },
    ]),
  }
}

/** UC13·UC8~12. 등록 응답은 실제 API와 같은 medication·medilight 묶음으로 반환한다. */
export async function createMedication(payload) {
  await wait()
  const medication = {
    ...clone(payload),
    id: `mock-medication-${medicationSequence++}`,
    ingredients: (payload.ingredients ?? []).map((ingredient) => ({
      ...ingredient,
      englishName: ingredient.englishName ?? ingredient.name,
    })),
    startDate: payload.startDate || '2026-09-03',
    analysisEligible: payload.analysisEligible ?? payload.type !== 'PRESCRIPTION',
  }
  medications.push(medication)
  return {
    status: 201,
    data: clone({ medication, medilight: analyzeMedications(medications) }),
  }
}

/** UC14. 현재 목록만 삭제하며 증상 기록의 medicationSnapshot은 유지한다. */
export async function deleteMedication(id) {
  await wait()
  const index = medications.findIndex((medication) => String(medication.id) === String(id))
  if (index < 0) throw new Error(`복용 항목을 찾을 수 없습니다: id=${id}`)
  medications.splice(index, 1)
  return { status: 204 }
}

/** UC20·21. 저장 시점의 복용 목록을 값으로 복사해 과거 스냅샷을 보존한다. */
export async function createSymptom(payload) {
  await wait()
  const symptom = {
    ...clone(payload),
    id: `mock-symptom-${symptomSequence++}`,
    medicationSnapshot: medications.map(({ id, name }) => ({ id, name })),
  }
  symptoms.unshift(symptom)
  return { status: 201, data: clone(symptom) }
}

export async function requestReport(payload) {
  await wait(650)
  return {
    status: 202,
    data: {
      id: `report-${payload.from}-${payload.to}`,
      jobStatus: 'COMPLETED',
      generatedAt: '2026-09-02 21:44',
      ...payload,
    },
  }
}
