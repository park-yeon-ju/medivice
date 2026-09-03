<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { useMediviceStore } from '@/stores/medivice'

const router = useRouter()
const store = useMediviceStore()
const today = '2026-09-02'
const date = ref(today)
const time = ref('21:30')
const selectedSymptoms = ref(['어지러움', '속쓰림'])
const note = ref('아침 약을 복용한 뒤 어지러웠음. 전날 잠을 4시간밖에 못 잤음.')
const customSymptom = ref('')
const symptomOptions = ['어지러움', '두통', '속쓰림', '메스꺼움', '발진 · 가려움', '부기', '기침', '피로감']

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
  <AppShell title="증상 기록" crumb="메인 /">
    <form class="symptom-layout" @submit.prevent="save">
      <section class="content-card symptom-form">
        <div class="section-title"><div><p class="eyebrow">SCR-SE-001 · UC20·21</p><h2>오늘의 몸 상태를 남겨주세요</h2></div><span class="chip neutral">인과관계 판정 안 함</span></div>
        <div class="field-grid two"><label class="form-field"><span>날짜 <b>*</b></span><input v-model="date" type="date" :max="today" /><small>오늘 이후 날짜는 선택할 수 없습니다.</small></label><label class="form-field"><span>작성 시각</span><input v-model="time" type="time" /><small>현재 시각이 기본값이며 수정할 수 있습니다.</small></label></div>
        <fieldset class="form-field"><legend>증상 <b>*</b><small>여러 개 선택 가능</small></legend><div class="symptom-options"><button v-for="symptom in symptomOptions" :key="symptom" type="button" :class="{ selected: selectedSymptoms.includes(symptom) }" @click="toggleSymptom(symptom)"><span aria-hidden="true">✓</span>{{ symptom }}</button></div></fieldset>
        <label class="form-field"><span>직접 입력</span><input v-model="customSymptom" placeholder="목록에 없는 증상을 입력하세요" /></label>
        <label class="form-field"><span>예외사항 · 메모</span><textarea v-model="note" rows="4"></textarea><small>관찰한 사실을 적고 원인을 단정하지 마세요.</small></label>
      </section>

      <aside class="snapshot-card">
        <div class="snapshot-heading"><span>복용 스냅샷</span><b>{{ store.medications.length }}건</b></div>
        <p>저장하면 이 날짜에 복용 중이던 항목을 함께 보존합니다. 약이 증상의 원인이라는 뜻은 아닙니다.</p>
        <ul><li v-for="medication in store.medications" :key="medication.id"><span class="group-dot" :class="medication.type === 'PRESCRIPTION' ? 'emerald' : 'mint'"></span>{{ medication.name }}</li></ul>
        <p v-if="store.error" class="field-error">{{ store.error }}</p>
        <div class="form-actions"><button class="button ghost" type="button" @click="router.push('/main')">취소</button><button class="button primary" type="submit" :disabled="!canSave || store.loading">{{ store.loading ? '저장 중…' : '기록 저장' }}</button></div>
      </aside>
    </form>
  </AppShell>
</template>
