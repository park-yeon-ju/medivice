<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: 'OK' },
  large: { type: Boolean, default: false },
})

const statusClass = computed(() => `s-${props.status.toLowerCase()}`)
const accessibleLabel = computed(() => {
  if (props.status === 'CRIT') return '빨강, 높은 주의'
  if (props.status === 'WARN') return '노랑, 주의'
  return '초록, 확인된 문제 없음'
})
</script>

<template>
  <span class="signal" :class="[statusClass, { large }]" role="img" :aria-label="accessibleLabel">
    <i></i><i></i><i></i>
  </span>
</template>
