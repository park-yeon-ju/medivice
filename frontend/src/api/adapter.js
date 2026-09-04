// 실제 Spring 백엔드 API(client.js)를 직접 호출하는 단일 진입점.
// 화면과 Pinia 스토어는 이 어댑터를 통해 백엔드(http://localhost:8080)와 직접 통신한다.
import * as realClient from './client.js'

// 백엔드 실제 API 모드로 직결 (Mock 데이터 사용 차단)
export const isMockApi = false
export const apiMode = 'real'

export const getDashboard = realClient.getDashboard
export const getMedilight = realClient.getMedilight
export const createMedication = realClient.createMedication
export const updateMedication = realClient.updateMedication
export const deleteMedication = realClient.deleteMedication
export const createSymptom = realClient.createSymptom
export const requestReport = realClient.requestReport
export const signup = realClient.signup
export const login = realClient.login
export const extractMedicationOcr = realClient.extractMedicationOcr

// 사용자 식별용 localStorage 관리 함수
export const getStoredLoginId = realClient.getStoredLoginId
export const setStoredLoginId = realClient.setStoredLoginId
