import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import MathText from './MathText.vue'

describe('MathText', () => {
  it('renders mixed Chinese text and formulas', () => {
    const wrapper = mount(MathText, { props: { text: '解方程 $2x+1=5$，则 $x=$____。' } })
    expect(wrapper.findAll('.katex')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('$')
  })

  it('never creates elements from user text', () => {
    const wrapper = mount(MathText, {
      props: { text: '<img src=x onerror=alert(1)> $<script>alert(1)</script>$' },
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.text()).toContain('script')
  })

  it('updates when a review item changes', async () => {
    const wrapper = mount(MathText, { props: { text: '$x=1$' } })
    await wrapper.setProps({ text: '$y=2$' })
    expect(wrapper.text()).toContain('y=2')
    expect(wrapper.text()).not.toContain('x=1')
  })

  /**
   * 题干文本本身永远不产生图片。
   *
   * <p>题图是有意设计的资产，只能从 `question.assets` 来（并且只能带鉴权读取）。
   * 如果这里会把文本里的 Markdown 图片语法或 `<img>` 渲染成图片，题干就成了注入图片的入口——
   * 而题干的内容来自 OCR 与学生输入，不能当作可信来源。
   */
  it('never turns text into images', () => {
    const wrapper = mount(MathText, {
      props: { text: '见图 ![](https://evil.example/x.png) <img src=x onerror=alert(1)>' },
    })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.findAll('figure')).toHaveLength(0)
    // 原样以文本呈现，而不是被当成图片语法解析掉。
    expect(wrapper.text()).toContain('![](https://evil.example/x.png)')
  })
})
