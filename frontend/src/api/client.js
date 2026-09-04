// 실제 Spring 백엔드(com.project.medivice)를 호출하는 클라이언트.
// mockClient.js와 같은 { status, data } 모양을 반환해 스토어 쪽 코드를 그대로 재사용한다.
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')

// 세션·토큰 인증이 없는 Sprint 1에서, 회원가입·로그인한 아이디를 이후 모든 요청에 실어 보내
// "누구의 요청인지"를 서버가 알 수 있게 한다(백엔드 DemoUserResolver + X-Medivice-User 헤더).
// 헤더가 없으면 서버는 고정 데모 사용자로 처리한다.
const LOGIN_ID_KEY = 'medivice_login_id'

export function getStoredLoginId() {
  try {
    return localStorage.getItem(LOGIN_ID_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setStoredLoginId(loginId) {
  try {
    if (loginId) localStorage.setItem(LOGIN_ID_KEY, loginId)
    else localStorage.removeItem(LOGIN_ID_KEY)
  } catch {
    // 프라이빗 브라우징 등으로 localStorage를 쓸 수 없으면 조용히 무시한다 — 세션 내 동작은 그대로 된다.
  }
}

// HTTP 헤더 값은 ISO-8859-1(라틴 문자)만 허용한다. 한글 아이디를 그대로 넣으면
// "String contains non ISO-8859-1 code point"로 fetch() 자체가 실패하므로 퍼센트 인코딩한다.
// 백엔드 CurrentUserFilter가 짝을 맞춰 디코딩한다.
function encodeLoginIdHeader(loginId) {
  return encodeURIComponent(loginId)
}

async function request(path, options = {}) {
  const loginId = getStoredLoginId()
  let response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: {
        'Content-Type': 'application/json',
        ...(loginId ? { 'X-Medivice-User': encodeLoginIdHeader(loginId) } : {}),
        ...options.headers,
      },
      ...options,
    })
  } catch (networkError) {
    // fetch()가 던지는 건 대개 서버 다운이지만, CORS 차단·네트워크 확장 프로그램 등도 같은
    // TypeError로 온다 — 브라우저가 준 실제 이유를 같이 보여줘야 "서버 켜져 있는데요?" 상황에서
    // 헛짚지 않는다.
    const reason = networkError instanceof Error ? networkError.message : String(networkError)
    throw new Error(`백엔드 서버(${BASE_URL})에 연결하지 못했습니다: ${reason}`, {
      cause: networkError,
    })
  }

  if (!response.ok) {
    let message = `요청이 실패했습니다 (${response.status})`
    try {
      const body = await response.json()
      if (body?.message) message = body.message
    } catch {
      // 응답 본문이 JSON이 아닌 경우 기본 메시지를 사용한다.
    }
    throw new Error(message)
  }

  if (response.status === 204) return null
  return response.json()
}

export async function getDashboard() {
  const data = await request('/api/dashboard')
  return { status: 200, data }
}

export async function getMedilight() {
  const data = await request('/api/medilight')
  return { status: 200, data }
}

/** UC13 수기 등록. MedicationCreateRequest 모양의 payload를 그대로 보낸다. */
export async function createMedication(payload) {
  const data = await request('/api/medications', { method: 'POST', body: JSON.stringify(payload) })
  return { status: 201, data }
}

/**
 * UC14 보완 복용 정보 수정.
 * MedicationCreateRequest 와 동일한 모양의 payload를 PUT /api/medications/:id 로 전송한다.
 * 백엔드는 수정한 항목과 재계산된 메디라이트({ medication, medilight })를 반환한다.
 */
export async function updateMedication(id, payload) {
  const data = await request(`/api/medications/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
  return { status: 200, data }
}

/** UC14 삭제. 응답 본문이 없으므로(204) 호출부가 medilight를 별도로 다시 조회해야 한다. */
export async function deleteMedication(id) {
  await request(`/api/medications/${id}`, { method: 'DELETE' })
  return { status: 204 }
}

export async function createSymptom(payload) {
  const data = await request('/api/symptoms', { method: 'POST', body: JSON.stringify(payload) })
  return { status: 201, data }
}

export async function requestReport(payload) {
  const data = await request('/api/reports', { method: 'POST', body: JSON.stringify(payload) })
  return { status: 202, data }
}

/** UC1 회원가입 — 이미 있는 아이디면 그 사용자를 그대로 돌려받는다(비밀번호는 검증하지 않음). */
export async function signup(payload) {
  const data = await request('/api/auth/signup', { method: 'POST', body: JSON.stringify(payload) })
  return { status: 201, data }
}

/** UC2 로그인 — 존재하지 않는 아이디면 404. */
export async function login(payload) {
  const data = await request('/api/auth/login', { method: 'POST', body: JSON.stringify(payload) })
  return { status: 200, data }
}

/**
 * UC8~10(EXT-1) 사진 인식 (비동기 OCR 계약).
 * 백엔드는 POST /api/medications/ocr 접수 시 202 Accepted와 함께 jobId를 돌려주고,
 * 실제 비전 AI 작업은 백그라운드에서 진행된다.
 * 프론트는 jobId를 받아 GET /api/medications/ocr/:jobId 를 폴링하여
 * 상태가 COMPLETED가 되면 인식된 약 목록(result)을 화면에 반환한다.
 */
export async function extractMedicationOcr(file) {
  const loginId = getStoredLoginId()
  const formData = new FormData()
  formData.append('file', file)

  let response
  try {
    response = await fetch(`${BASE_URL}/api/medications/ocr`, {
      method: 'POST',
      headers: loginId ? { 'X-Medivice-User': encodeLoginIdHeader(loginId) } : {},
      body: formData,
    })
  } catch (networkError) {
    // fetch()가 던지는 건 대개 서버 다운이지만, CORS 차단·네트워크 확장 프로그램 등도 같은
    // TypeError로 온다 — 브라우저가 준 실제 이유를 같이 보여줘야 "서버 켜져 있는데요?" 상황에서
    // 헛짚지 않는다.
    const reason = networkError instanceof Error ? networkError.message : String(networkError)
    throw new Error(`백엔드 서버(${BASE_URL})에 연결하지 못했습니다: ${reason}`, {
      cause: networkError,
    })
  }

  if (!response.ok) {
    let message = `이미지 인식에 실패했습니다 (${response.status})`
    try {
      const body = await response.json()
      if (body?.message) message = body.message
    } catch {
      // 응답 본문이 JSON이 아닌 경우 기본 메시지를 사용한다.
    }
    throw new Error(message)
  }

  const initialData = await response.json()

  // 1. 이미 배열로 결과가 온 경우 (하위 호환성 또는 Mock 모드) 즉시 반환
  if (Array.isArray(initialData)) {
    return { status: 200, data: initialData }
  }

  // 2. 백엔드 비동기 계약: 202 Accepted + OcrJobDto { jobId, status, result, error }
  const jobId = initialData?.jobId
  if (!jobId) {
    // jobId가 없으나 result 필드가 배열인 경우
    if (Array.isArray(initialData?.result)) {
      return { status: 200, data: initialData.result }
    }
    return { status: 200, data: initialData }
  }

  // 3. jobId로 백엔드 폴링 (최대 60초 대기, 600ms 간격)
  const pollIntervalMs = 600
  const maxTimeoutMs = 60000
  const startTime = Date.now()

  while (Date.now() - startTime < maxTimeoutMs) {
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs))

    const job = await request(`/api/medications/ocr/${jobId}`)
    if (!job) continue

    if (job.status === 'COMPLETED') {
      return { status: 200, data: job.result ?? [] }
    }
    if (job.status === 'FAILED') {
      throw new Error(job.error || '이미지 분석에 실패했습니다.')
    }
    // PENDING 또는 PROCESSING 상태이면 계속 폴링
  }

  throw new Error('이미지 인식 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.')
}
