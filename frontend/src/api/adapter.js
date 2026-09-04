// VITE_USE_MOCK_API 값으로 실제 Spring API와 개발용 Mock API를 선택하는 단일 진입점.
// 화면과 Pinia 스토어는 구현체를 직접 알지 않고 확정된 함수·응답 규격에만 의존한다.
import * as realClient from './client.js'

export const isMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true'
export const apiMode = isMockApi ? 'mock' : 'real'

// production build에서 Mock 데이터가 번들에 섞이지 않도록 선택된 구현체만 지연 로드한다.
// import()가 반환한 모듈은 브라우저가 캐시하므로 매 API 호출마다 다시 다운로드되지 않는다.
const loadActiveClient = isMockApi
  ? () => import('./mockClient.js')
  : () => Promise.resolve(realClient)

async function callApi(method, args) {
  const client = await loadActiveClient()
  return client[method](...args)
}

export const getDashboard = (...args) => callApi('getDashboard', args)
export const getMedilight = (...args) => callApi('getMedilight', args)
export const createMedication = (...args) => callApi('createMedication', args)
export const updateMedication = (...args) => {
  // 백엔드 수정 계약이 확정되지 않았으므로 회의용 수정 기능은 Mock 모드에서만 제공한다.
  if (!isMockApi) throw new Error('복용 정보 수정 API가 아직 연결되지 않았습니다.')
  return callApi('updateMedication', args)
}
export const deleteMedication = (...args) => callApi('deleteMedication', args)
export const createSymptom = (...args) => callApi('createSymptom', args)
export const requestReport = (...args) => callApi('requestReport', args)
export const signup = (...args) => callApi('signup', args)
export const login = (...args) => callApi('login', args)
export const extractMedicationOcr = (...args) => callApi('extractMedicationOcr', args)

// 사용자 식별용 localStorage 규격은 실제·Mock 모드가 공유해야 모드 전환 후에도 동작이 일관된다.
export const getStoredLoginId = realClient.getStoredLoginId
export const setStoredLoginId = realClient.setStoredLoginId
