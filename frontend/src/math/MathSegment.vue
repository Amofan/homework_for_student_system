<!--
  唯一把 KaTeX 生成的 HTML 交给 innerHTML 的组件。用户文本永远只走另一条路
  （MathText.vue 里的文本插值），因此这里注入的内容只可能来自 renderTex。
-->
<script lang="ts">
import { defineComponent, h } from 'vue'

import { renderTex } from './render'

export default defineComponent({
  name: 'MathSegment',
  props: {
    tex: { type: String, required: true },
    display: { type: Boolean, required: true },
  },
  setup(props) {
    return () => h('span', {
      class: ['math-segment', { 'math-segment--display': props.display }],
      innerHTML: renderTex(props.tex, props.display),
    })
  },
})
</script>
