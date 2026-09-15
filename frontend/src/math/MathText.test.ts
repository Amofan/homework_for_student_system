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
})
