<!--
  数学文本的通用显示组件：题干、学生作答、评分说明都走这里。
  文本段用 Vue 插值（自动转义），公式段交给 MathSegment。
-->
<script setup lang="ts">
import { computed } from 'vue'

import MathSegment from './MathSegment.vue'
import { splitMath } from './segments'

const props = defineProps<{ text: string }>()
const segments = computed(() => splitMath(props.text))
</script>

<template>
  <template v-for="(segment, index) in segments" :key="index">
    <span v-if="segment.type === 'text'">{{ segment.value }}</span>
    <MathSegment v-else :tex="segment.value" :display="segment.display" />
  </template>
</template>
