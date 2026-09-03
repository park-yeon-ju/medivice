<!--
  MedicationRow.vue
  복용 항목 한 건의 분류·성분·복용 정보를 목록 행으로 표시하는 공통 컴포넌트.

  처방약 여부와 성분 중복 여부를 함께 보여주고, 선택 정보가 없으면 확인된 값만 조합해 표시한다.
  관련 UC: UC14, UC15 / 화면: SCR-MAIN-001, SCR-MAIN-003
-->
<script setup>
import { computed } from 'vue'

const props = defineProps({
  medication: { type: Object, required: true },
  showActions: { type: Boolean, default: false },
  duplicatedIngredients: { type: Array, default: () => [] },
})

defineEmits(['remove'])

const isPrescription = computed(() => props.medication.type === 'PRESCRIPTION')
const isDuplicate = computed(() =>
  props.medication.ingredients.some((ingredient) =>
    props.duplicatedIngredients.includes(ingredient.name),
  ),
)
const ingredientLabel = computed(() => {
  if (props.medication.ingredientNote) return props.medication.ingredientNote
  return props.medication.ingredients
    .map(
      (ingredient) => `${ingredient.name} ${ingredient.amount.toLocaleString()} ${ingredient.unit}`,
    )
    .join(' · ')
})
const dosageLabel = computed(() => {
  if (!props.medication.timesPerDay) {
    // 최대 횟수가 없는 경우 임의의 복용 횟수를 추정하지 않고 정보가 없다는 사실만 표시한다.
    return props.medication.maxTimesPerDay
      ? `필요 시 · 하루 최대 ${props.medication.maxTimesPerDay}회`
      : '필요 시 · 횟수 정보 없음'
  }
  const timing = props.medication.timing ? ` · ${props.medication.timing}` : ''
  return `1회 ${props.medication.dose}${props.medication.doseUnit} · 하루 ${props.medication.timesPerDay}회${timing}`
})
</script>

<template>
  <article class="medication-row">
    <span
      class="category-stripe"
      :class="isPrescription ? 'rx' : isDuplicate ? 'warn' : 'supplement'"
    ></span>
    <div class="medication-main">
      <div class="medication-title">
        <b>{{ medication.name }}</b>
        <span v-if="isDuplicate" class="chip warn">성분 중복</span>
        <span v-else class="chip" :class="isPrescription ? 'rx' : 'neutral'">
          {{ isPrescription ? '처방약' : medication.type === 'OTC' ? '상비약' : '영양제' }}
        </span>
      </div>
      <p>{{ ingredientLabel }}</p>
      <div v-if="medication.aiExplanation" class="ai-summary">
        <span class="chip ai">AI 설명</span>
        <span>{{ medication.aiExplanation }}</span>
      </div>
    </div>
    <div class="medication-dose">
      <div>
        <span>복용법</span>
        <b>{{ dosageLabel }}</b>
      </div>
      <div v-if="medication.startDate">
        <span>복용 시작일</span>
        <time :datetime="medication.startDate">{{ medication.startDate }}</time>
      </div>
    </div>
    <div v-if="showActions" class="row-actions">
      <RouterLink
        class="text-button"
        :to="`/medications/${medication.id}/edit`"
        :aria-label="`${medication.name} 복용 정보 수정`"
        >수정</RouterLink
      >
      <button class="text-button" type="button" @click="$emit('remove', medication.id)">
        삭제
      </button>
    </div>
  </article>
</template>
