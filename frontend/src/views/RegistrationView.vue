<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { useMediviceStore } from '@/stores/medivice'

const { mode } = defineProps({
  mode: { type: String, default: 'upload' },
})

const router = useRouter()
const store = useMediviceStore()
const registrationType = ref('SUPPLEMENT')
const fileName = ref('')
const ocrFile = ref(null)
const department = ref('내과')
const uploadError = ref('')
const starting = ref(false)

const manual = ref({
  name: '비타민D 1000IU',
  ingredient: '비타민D',
  amount: 1000,
  unit: 'IU',
  dose: 1,
  doseUnit: '정',
  timesPerDay: 1,
  type: 'SUPPLEMENT',
  reason: '뼈 건강',
})

const previewTotal = computed(
  () => manual.value.amount * manual.value.dose * manual.value.timesPerDay,
)
const manualError = ref('')
const manualSaving = ref(false)

// SCR-REG-002 확인 화면에서 사용자가 고칠 수 있는 초안들. 사진 한 장(특히 약봉투)에서 서로 다른
// 약이 여러 개 나올 수 있어 배열로 다룬다 — store.ocrDrafts(백엔드 응답, 항목마다 하나)를 각각
// 편집 가능한 폼으로 옮기고, 여기서 고친 값이 그대로 등록 payload가 된다.
const confirmDrafts = ref([])
const confirmIncluded = ref([])
const confirmError = ref('')
const confirmSaving = ref(false)
const confirmProgress = ref('')

const includedCount = computed(() => confirmIncluded.value.filter(Boolean).length)

function makeDraft(d) {
  return {
    type: d?.type ?? 'PRESCRIPTION',
    name: d?.name ?? '',
    ingredients: d?.ingredients?.length
      ? d.ingredients.map((i) => ({ name: i.name, amount: i.amount, unit: i.unit }))
      : [{ name: '', amount: null, unit: 'mg' }],
    dose: d?.dose ?? 1,
    doseUnit: '정',
    timesPerDay: d?.timesPerDay ?? 1,
    hospital: d?.hospital ?? '',
    department: d?.department ?? '내과',
    duration: d?.duration ?? '',
    reason: '',
    rows: d?.rows ?? [],
    note: d?.note ?? null,
  }
}

function seedConfirmDrafts() {
  const list = store.ocrDrafts?.length ? store.ocrDrafts : [null]
  confirmDrafts.value = list.map(makeDraft)
  confirmIncluded.value = confirmDrafts.value.map(() => true)
}

function addIngredientRow(draftIndex) {
  confirmDrafts.value[draftIndex].ingredients.push({ name: '', amount: null, unit: 'mg' })
}

function removeIngredientRow(draftIndex, ingredientIndex) {
  const ingredients = confirmDrafts.value[draftIndex].ingredients
  if (ingredients.length <= 1) return
  ingredients.splice(ingredientIndex, 1)
}

function selectFile(event) {
  const file = event.target.files?.[0] ?? null
  ocrFile.value = file
  fileName.value = file?.name ?? ''
}

/** UC8~10(EXT-1) — 실제 POST /api/medications/ocr(비전 AI, medivice.ai.provider 설정에 따름)을 호출한다. */
async function startOcr() {
  uploadError.value = ''
  if (!ocrFile.value) {
    uploadError.value = '사진을 먼저 선택해주세요.'
    return
  }
  starting.value = true
  try {
    await store.runOcr(ocrFile.value)
    seedConfirmDrafts()
    router.push('/main/register-confirm')
  } catch (caughtError) {
    uploadError.value = caughtError instanceof Error ? caughtError.message : '인식에 실패했습니다.'
  } finally {
    starting.value = false
  }
}

/**
 * SCR-REG-002 "선택한 N건 등록" — 체크된 약을 실제 POST /api/medications로 하나씩 등록한다.
 * 사용자가 확인한 값만 저장한다(D-4). 여러 건이라 순서대로 하나씩 처리하고, 중간에 실패하면
 * 그 항목 이름과 함께 멈춘다 — 이미 등록된 앞쪽 항목은 그대로 남는다(부분 성공 허용).
 */
async function confirmOcr() {
  confirmError.value = ''
  const targets = confirmDrafts.value.filter((_, index) => confirmIncluded.value[index])

  if (targets.length === 0) {
    confirmError.value = '등록할 약을 하나 이상 선택해주세요.'
    return
  }
  for (const draft of targets) {
    const hasIngredient = draft.ingredients.some((i) => i.name && i.amount)
    if (!draft.name || !hasIngredient || !draft.reason) {
      confirmError.value = `"${draft.name || '이름 없는 항목'}"의 제품명·성분·등록 사유를 확인해주세요.`
      return
    }
  }

  confirmSaving.value = true
  try {
    for (let i = 0; i < targets.length; i += 1) {
      const draft = targets[i]
      confirmProgress.value =
        targets.length > 1 ? `${i + 1}/${targets.length}건 등록 중…` : '등록 중…'
      const isPrescription = draft.type === 'PRESCRIPTION'
      const cleanIngredients = draft.ingredients
        .filter((ing) => ing.name && ing.amount)
        .map((ing) => ({ name: ing.name, amount: Number(ing.amount), unit: ing.unit }))
      await store.createMedication({
        type: draft.type,
        name: draft.name,
        ingredients: cleanIngredients,
        dose: Number(draft.dose),
        doseUnit: draft.doseUnit,
        timesPerDay: Number(draft.timesPerDay),
        reason: draft.reason,
        hospital: isPrescription ? draft.hospital : undefined,
        department: isPrescription ? draft.department : undefined,
        duration: isPrescription ? draft.duration : undefined,
      })
    }
    router.push('/main')
  } catch (caughtError) {
    confirmError.value =
      caughtError instanceof Error
        ? caughtError.message
        : '일부 항목 등록에 실패했습니다. 남은 항목을 확인해주세요.'
  } finally {
    confirmSaving.value = false
    confirmProgress.value = ''
  }
}

/** UC13 수기 등록 — 실제 POST /api/medications를 호출한다. */
async function saveManual() {
  manualError.value = ''
  manualSaving.value = true
  const isPrescription = manual.value.type === 'PRESCRIPTION'
  try {
    await store.createMedication({
      type: manual.value.type,
      name: manual.value.name,
      ingredients: [
        {
          name: manual.value.ingredient,
          amount: Number(manual.value.amount),
          unit: manual.value.unit,
        },
      ],
      dose: Number(manual.value.dose),
      doseUnit: manual.value.doseUnit,
      timesPerDay: Number(manual.value.timesPerDay),
      reason: manual.value.reason,
      // 수기 입력 폼에는 병원·기간 필드가 없다. 처방약을 골라도 진료과 정도만 보내고
      // 나머지는 비워 보낸다(백엔드에서 선택 항목이라 등록 자체는 막히지 않는다).
      department: isPrescription ? department.value : undefined,
    })
    router.push('/main')
  } catch (caughtError) {
    manualError.value =
      caughtError instanceof Error
        ? caughtError.message
        : '등록에 실패했습니다. 잠시 후 다시 시도해주세요.'
  } finally {
    manualSaving.value = false
  }
}

if (mode === 'confirm' && confirmDrafts.value.length === 0) {
  seedConfirmDrafts()
}
</script>

<template>
  <AppShell title="약 등록" crumb="메인 /">
    <section class="modal-page">
      <div v-if="mode === 'upload'" class="dialog-card">
        <header class="dialog-header">
          <div>
            <p class="eyebrow">SCR-REG-001 · UC8·11·13</p>
            <h2>약 등록</h2>
          </div>
          <button class="icon-button" type="button" aria-label="닫기" @click="router.push('/main')">
            ×
          </button>
        </header>
        <div class="dialog-body">
          <div class="segmented-select">
            <button
              type="button"
              :class="{ active: registrationType === 'SUPPLEMENT' }"
              @click="registrationType = 'SUPPLEMENT'"
            >
              <b>영양제 · 상비약</b><span>제품 라벨 사진</span>
            </button>
            <button
              type="button"
              :class="{ active: registrationType === 'PRESCRIPTION' }"
              @click="registrationType = 'PRESCRIPTION'"
            >
              <b>처방약</b><span>처방전 · 약봉투 사진</span>
            </button>
          </div>
          <label class="drop-zone" :class="{ populated: fileName }">
            <input type="file" accept="image/jpeg,image/png,image/webp" @change="selectFile" />
            <span class="upload-icon" aria-hidden="true">↑</span>
            <b>{{ fileName || '사진을 끌어다 놓거나 파일을 선택하세요' }}</b>
            <small>JPG · PNG · WEBP · 최대 10MB · 글자가 선명하게 보이도록 촬영해주세요.</small>
            <span class="button secondary">파일 선택</span>
          </label>
          <div class="info-note">
            <b>비전 AI가 사진에서 병원·제품명·성분·용법을 읽어냅니다.</b
            ><span>다음 화면에서 값을 확인·수정한 뒤에만 실제로 저장됩니다.</span>
          </div>
          <p v-if="uploadError" class="field-error">{{ uploadError }}</p>
          <RouterLink class="manual-link" to="/main/register-manual"
            >사진 없이 직접 입력 →</RouterLink
          >
        </div>
        <footer class="dialog-footer">
          <button class="button ghost" type="button" @click="router.push('/main')">취소</button
          ><button class="button primary" type="button" :disabled="starting" @click="startOcr">
            {{ starting ? '인식 중…' : '인식 시작' }}
          </button>
        </footer>
      </div>

      <div v-else-if="mode === 'confirm' && confirmDrafts.length" class="dialog-card wide-dialog">
        <header class="dialog-header">
          <div>
            <p class="eyebrow">SCR-REG-002 · UC9·10·12</p>
            <h2>인식된 약 {{ confirmDrafts.length }}건을 확인해주세요</h2>
          </div>
          <span class="chip ai">AI · OCR</span>
        </header>
        <div class="dialog-body">
          <p class="helper-copy">
            사진 한 장에서 읽은 내용입니다. 항목마다 값을 확인·수정하고, 등록할 것만 체크한 뒤 한
            번에 저장하세요. 체크 전까지 저장되지 않습니다.
          </p>

          <section
            v-for="(draft, di) in confirmDrafts"
            :key="di"
            class="ocr-medication-card"
            :class="{ excluded: !confirmIncluded[di] }"
          >
            <header class="ocr-medication-header">
              <label class="checkbox-line"
                ><input type="checkbox" v-model="confirmIncluded[di]" /><b>{{
                  draft.name || `약 ${di + 1}`
                }}</b></label
              >
            </header>

            <template v-if="confirmIncluded[di]">
              <section v-if="draft.rows.length" class="ocr-card">
                <div v-for="row in draft.rows" :key="row.key" class="ocr-row">
                  <span>{{ row.key }}</span
                  ><b>{{ row.value }}</b
                  ><small
                    v-if="row.confidence != null"
                    :class="row.confidence < 0.7 ? 'low' : 'high'"
                    >신뢰도 {{ row.confidence.toFixed(2) }}</small
                  >
                </div>
              </section>
              <div v-if="draft.note" class="warning-note">
                <b>{{ draft.note }}</b
                ><span>아래 값을 다시 한 번 확인해주세요.</span>
              </div>

              <div class="field-grid two">
                <label class="form-field"
                  ><span>제품명 <b>*</b></span
                  ><input v-model="draft.name" required /></label
                ><label class="form-field"
                  ><span>분류 <b>*</b></span
                  ><select v-model="draft.type">
                    <option value="PRESCRIPTION">처방약</option>
                    <option value="OTC">상비약</option>
                    <option value="SUPPLEMENT">영양제</option>
                  </select></label
                >
              </div>

              <div
                v-for="(ingredient, ii) in draft.ingredients"
                :key="ii"
                class="field-grid ingredient-grid"
              >
                <label class="form-field"
                  ><span>성분 <b>*</b></span
                  ><input v-model="ingredient.name" required
                /></label>
                <label class="form-field"
                  ><span>함량 <b>*</b></span
                  ><input v-model.number="ingredient.amount" type="number" min="0" required
                /></label>
                <label class="form-field"
                  ><span>단위</span
                  ><select v-model="ingredient.unit">
                    <option>mg</option>
                    <option>mcg</option>
                    <option>IU</option>
                    <option>mL</option>
                  </select></label
                >
                <button
                  v-if="draft.ingredients.length > 1"
                  type="button"
                  class="icon-button"
                  aria-label="성분 삭제"
                  @click="removeIngredientRow(di, ii)"
                >
                  ×
                </button>
              </div>
              <button type="button" class="button text" @click="addIngredientRow(di)">
                + 성분 추가 (복합제)
              </button>

              <div class="field-grid three">
                <label class="form-field"
                  ><span>1회 투여량 <b>*</b></span
                  ><input
                    v-model.number="draft.dose"
                    type="number"
                    min="0.5"
                    step="0.5"
                    required /></label
                ><label class="form-field"
                  ><span>제형 단위</span
                  ><select v-model="draft.doseUnit">
                    <option>정</option>
                    <option>캡슐</option>
                    <option>포</option>
                  </select></label
                ><label class="form-field"
                  ><span>1일 횟수 <b>*</b></span
                  ><input
                    v-model.number="draft.timesPerDay"
                    type="number"
                    min="1"
                    max="10"
                    required
                /></label>
              </div>

              <div v-if="draft.type === 'PRESCRIPTION'" class="field-grid two">
                <label class="form-field"
                  ><span>병원명</span><input v-model="draft.hospital"
                /></label>
                <label class="form-field"
                  ><span>진료과 대분류</span
                  ><select v-model="draft.department">
                    <option>내과</option>
                    <option>이비인후과</option>
                    <option>정형외과</option>
                    <option>피부과</option>
                    <option>안과</option>
                    <option>가정의학과</option>
                    <option>기타</option>
                  </select></label
                >
              </div>
              <div v-if="draft.type === 'PRESCRIPTION'" class="form-field">
                <span>복용 기간</span><input v-model="draft.duration" placeholder="예: 30일분" />
              </div>
              <label class="form-field"
                ><span>등록 사유 <b>*</b></span
                ><input v-model="draft.reason" placeholder="예: 고혈압, 뼈 건강" required /><small
                  >질병 소분류나 제품을 먹는 이유는 사용자가 직접 입력합니다.</small
                ></label
              >
            </template>
          </section>

          <p v-if="confirmError" class="field-error">{{ confirmError }}</p>
          <RouterLink class="manual-link" to="/main/register-manual"
            >직접 입력으로 전환 →</RouterLink
          >
        </div>
        <footer class="dialog-footer">
          <button class="button ghost" type="button" @click="router.push('/main/register')">
            아니요 · 다시 촬영</button
          ><button
            class="button primary"
            type="button"
            :disabled="confirmSaving || includedCount === 0"
            @click="confirmOcr"
          >
            {{ confirmSaving ? confirmProgress : `선택한 ${includedCount}건 등록` }}
          </button>
        </footer>
      </div>

      <form v-else class="dialog-card wide-dialog" @submit.prevent="saveManual">
        <header class="dialog-header">
          <div>
            <p class="eyebrow">SCR-REG-003 · UC13</p>
            <h2>직접 입력</h2>
          </div>
          <button class="icon-button" type="button" aria-label="닫기" @click="router.push('/main')">
            ×
          </button>
        </header>
        <div class="dialog-body">
          <div class="info-note">
            <b>지금은 직접 입력만 실제로 저장됩니다.</b
            ><span>사진으로 등록(OCR)은 준비 중인 기능이라 아래 값을 입력해 등록해주세요.</span>
          </div>
          <div class="field-grid two">
            <label class="form-field"
              ><span>제품명 <b>*</b></span
              ><input v-model="manual.name" required /></label
            ><label class="form-field"
              ><span>분류 <b>*</b></span
              ><select v-model="manual.type">
                <option value="SUPPLEMENT">영양제</option>
                <option value="OTC">상비약</option>
                <option value="PRESCRIPTION">처방약</option>
              </select></label
            >
          </div>
          <div class="field-grid ingredient-grid">
            <label class="form-field"
              ><span>성분 <b>*</b></span
              ><input v-model="manual.ingredient" required /></label
            ><label class="form-field"
              ><span>함량 <b>*</b></span
              ><input v-model.number="manual.amount" type="number" min="0" required /></label
            ><label class="form-field"
              ><span>단위</span
              ><select v-model="manual.unit">
                <option>IU</option>
                <option>mg</option>
                <option>mcg</option>
              </select></label
            >
          </div>
          <div class="field-grid three">
            <label class="form-field"
              ><span>1회 투여량 <b>*</b></span
              ><input
                v-model.number="manual.dose"
                type="number"
                min="0.5"
                step="0.5"
                required /></label
            ><label class="form-field"
              ><span>제형 단위</span
              ><select v-model="manual.doseUnit">
                <option>정</option>
                <option>캡슐</option>
                <option>포</option>
              </select></label
            ><label class="form-field"
              ><span>1일 횟수 <b>*</b></span
              ><input v-model.number="manual.timesPerDay" type="number" min="1" max="10" required
            /></label>
          </div>
          <label class="form-field"><span>등록 사유</span><input v-model="manual.reason" /></label>
          <div class="calculation-preview">
            <span>계산 미리보기</span
            ><code
              >{{ Number(manual.amount).toLocaleString() }} {{ manual.unit }} × {{ manual.dose
              }}{{ manual.doseUnit }} × {{ manual.timesPerDay }}회</code
            ><b>하루 {{ previewTotal.toLocaleString() }} {{ manual.unit }}</b>
          </div>
          <p v-if="manualError" class="field-error">{{ manualError }}</p>
        </div>
        <footer class="dialog-footer">
          <RouterLink class="button ghost" to="/main/register">사진으로 등록 (준비 중)</RouterLink
          ><button class="button text" type="button" @click="router.push('/main')">취소</button
          ><button class="button primary" type="submit" :disabled="manualSaving">
            {{ manualSaving ? '등록 중…' : '등록' }}
          </button>
        </footer>
      </form>
    </section>
  </AppShell>
</template>

<style scoped>
.ocr-medication-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 16px;
  background: var(--surface-2, var(--surface));
}

.ocr-medication-card.excluded {
  opacity: 0.55;
}

.ocr-medication-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.checkbox-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

.checkbox-line input[type='checkbox'] {
  width: 18px;
  height: 18px;
  accent-color: var(--lime-hover);
}
</style>
