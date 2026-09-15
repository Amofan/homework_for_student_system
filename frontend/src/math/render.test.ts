import { describe, expect, it } from 'vitest'

import { renderTex } from './render'

// 真实语料：题库与演示数据里实际出现过的写法。
const valid = [
  '2x+1=5', '3(x-1)=9', '(-2x)^2', '2x^2y',
  '(x+3)(x-3)', String.raw`AB \parallel CD`,
  String.raw`\angle 1=65°`, String.raw`\angle 2=`,
]

describe('renderTex', () => {
  it.each(valid)('renders real project formula %s', (tex) => {
    const html = renderTex(tex, false)
    expect(html).toContain('katex')
    expect(html).not.toContain('katex-error')
  })

  it('keeps malformed formulas visible as a red KaTeX error', () => {
    expect(renderTex(String.raw`\frac{1}{2`, false)).toContain('katex-error')
  })

  it('renders cases on the page even though Word cannot convert them', () => {
    expect(renderTex(String.raw`\begin{cases}x+y=3\\x-y=1\end{cases}`, true))
      .not.toContain('katex-error')
  })
})
