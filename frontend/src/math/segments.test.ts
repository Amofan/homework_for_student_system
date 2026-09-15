import { describe, expect, it } from 'vitest'

import { splitMath } from './segments'

describe('splitMath', () => {
  it('splits inline and display formulas', () => {
    expect(splitMath('a$x$b')).toEqual([
      { type: 'text', value: 'a' },
      { type: 'math', value: 'x', display: false },
      { type: 'text', value: 'b' },
    ])
    expect(splitMath('$$x+1$$')).toEqual([{ type: 'math', value: 'x+1', display: true }])
  })

  it.each(['价格是 $5', '$ x$', '$x $', '价格 $5 元，$8 元', '$$', '$ $'])(
    'keeps invalid or ambiguous input as text: %s',
    (input) => expect(splitMath(input)).toEqual([{ type: 'text', value: input }]),
  )

  it('turns an escaped dollar into literal text', () => {
    expect(splitMath(String.raw`价格是 \$5`)).toEqual([{ type: 'text', value: '价格是 $5' }])
  })

  it('degrades an unclosed display delimiter deterministically', () => {
    expect(splitMath('$$x$')).toEqual([
      { type: 'text', value: '$' },
      { type: 'math', value: 'x', display: false },
    ])
  })

  it('handles adjacent and Chinese-surrounded formulas', () => {
    expect(splitMath('$a$$b$')).toEqual([
      { type: 'math', value: 'a', display: false },
      { type: 'math', value: 'b', display: false },
    ])
    expect(splitMath('解方程 $2x+1=5$，则 $x=$____。')).toEqual([
      { type: 'text', value: '解方程 ' },
      { type: 'math', value: '2x+1=5', display: false },
      { type: 'text', value: '，则 ' },
      { type: 'math', value: 'x=', display: false },
      { type: 'text', value: '____。' },
    ])
  })
})
