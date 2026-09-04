<!--
  SymptomEntryView.vue
  사용자가 관찰한 증상과 당시 복용 목록 스냅샷을 함께 기록하는 입력 화면.

  사용자가 관찰한 사실만 기록하며 약과 증상의 관계를 판정하지 않는다.
  관련 UC: UC20, UC21 / 화면: SCR-SE-001
-->
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { OBSERVABLE_SYMPTOMS } from '@/healthOptions'
import AppShell from '@/layouts/AppShell.vue'
import { useMediviceStore } from '@/stores/medivice'

const router = useRouter()
const store = useMediviceStore()
const now = new Date()
// 날짜·시각 입력의 기본값은 사용자의 브라우저 지역 시간을 사용해 실제 '오늘'과 어긋나지 않게 한다.
const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
const date = ref(today)
const time = ref(
  `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`,
)
const selectedSymptoms = ref(['어지러움', '속쓰림'])
const note = ref('')
const customSymptom = ref('')
// 공통 목록은 이상사례를 원인으로 단정하지 않고 사용자가 관찰한 증상만 기록하게 한다.
const symptomOptions = OBSERVABLE_SYMPTOMS

const canSave = computed(() => selectedSymptoms.value.length > 0 || customSymptom.value.trim())

onMounted(() => store.loadDashboard())

function toggleSymptom(symptom) {
  selectedSymptoms.value = selectedSymptoms.value.includes(symptom)
    ? selectedSymptoms.value.filter((item) => item !== symptom)
    : [...selectedSymptoms.value, symptom]
}

async function save() {
  if (!canSave.value) return
  const symptoms = customSymptom.value.trim()
    ? [...selectedSymptoms.value, customSymptom.value.trim()]
    : selectedSymptoms.value
  try {
    await store.addSymptom({ date: date.value, time: time.value, symptoms, note: note.value })
    router.push('/my/symptoms')
  } catch {
    // store.error에 메시지가 이미 반영되어 있다 — 아래에서 그대로 노출한다.
  }
}
</script>

<template>
  <AppShell title="증상 기록">
    <form class="symptom-layout" @submit.prevent="save">
      <section class="content-card symptom-form">
        <div class="section-title">
          <div><h2>증상 기록</h2></div>
          <span class="chip neutral">약과 증상의 관계는 판단하지 않아요</span>
        </div>
        <div class="field-grid two">
          <label class="form-field"
            ><span>날짜 <b>*</b></span
            ><input v-model="date" type="date" :max="today" /><small
              >오늘 이후 날짜는 선택할 수 없습니다.</small
            ></label
          ><label class="form-field"
            ><span>작성 시각</span><input v-model="time" type="time" /><small
              >현재 시각이 기본값이며 수정할 수 있습니다.</small
            ></label
          >
        </div>
        <fieldset class="form-field">
          <legend>증상 <b>*</b><small>여러 개 선택 가능</small></legend>
          <div class="symptom-options">
            <button
              v-for="symptom in symptomOptions"
              :key="symptom"
              type="button"
              :class="{ selected: selectedSymptoms.includes(symptom) }"
              @click="toggleSymptom(symptom)"
            >
              <span aria-hidden="true">✓</span>{{ symptom }}
            </button>
          </div>
        </fieldset>
        <label class="form-field"
          ><span>직접 입력</span
          ><input v-model="customSymptom" placeholder="목록에 없는 증상을 입력하세요"
        /></label>
        <label class="form-field"
          ><span>예외사항 · 메모</span
          ><textarea
            v-model="note"
            rows="4"
            placeholder="증상이 나타나는 의심 정황을 입력하세요."
          ></textarea
          ><small>관찰한 사실을 적고 원인을 단정하지 마세요.</small></label
        >
      </section>

      <aside class="snapshot-card">
        <div class="snapshot-heading">
          <span>복용 스냅샷</span><b>{{ store.medications.length }}건</b>
        </div>
        <p>
          저장하면 이 날짜에 복용 중이던 항목을 함께 보존합니다. 약이 증상의 원인이라는 뜻은
          아닙니다.
        </p>
        <ul>
          <li v-for="medication in store.medications" :key="medication.id">
            <span
              class="group-dot"
              :class="medication.type === 'PRESCRIPTION' ? 'emerald' : 'mint'"
            ></span
            >{{ medication.name }}
          </li>
        </ul>
        <p v-if="store.error" class="field-error">{{ store.error }}</p>
        <div class="form-actions">
          <button class="button ghost" type="button" @click="router.push('/main')">취소</button
          ><button class="button primary" type="submit" :disabled="!canSave || store.loading">
            {{ store.loading ? '저장 중…' : '기록 저장' }}
          </button>
        </div>
      </aside>
    </form>
  </AppShell>
</template>
