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
  props.medication.ingredients.some((ingredient) => props.duplicatedIngredients.includes(ingredient.name)),
)
const ingredientLabel = computed(() => {
  if (props.medication.ingredientNote) return props.medication.ingredientNote
  return props.medication.ingredients
    .map((ingredient) => `${ingredient.name} ${ingredient.amount.toLocaleString()} ${ingredient.unit}`)
    .join(' · ')
})
const dosageLabel = computed(() => {
  if (!props.medication.timesPerDay) return `필요 시 · 하루 최대 ${props.medication.maxTimesPerDay}회`
  const timing = props.medication.timing ? ` · ${props.medication.timing}` : ''
  return `1회 ${props.medication.dose}${props.medication.doseUnit} · 하루 ${props.medication.timesPerDay}회${timing}`
})
</script>

<template>
  <article class="medication-row">
    <span class="category-stripe" :class="isPrescription ? 'rx' : isDuplicate ? 'warn' : 'supplement'"></span>
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
      <b>{{ dosageLabel }}</b>
      <span v-if="medication.startDate">{{ medication.startDate }} ~</span>
    </div>
    <div v-if="showActions" class="row-actions">
      <button class="text-button" type="button">수정</button>
      <button class="text-button" type="button" @click="$emit('remove', medication.id)">삭제</button>
    </div>
  </article>
</template>
