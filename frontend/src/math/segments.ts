/**
 * 把数学文本切成「纯文本段」与「公式段」两类。这个模块不依赖 Vue、DOM 或 KaTeX，
 * 只做定界符识别，因此它的降级规则可以单独测试。
 */

export type Segment =
  | { type: 'text'; value: string }
  | { type: 'math'; value: string; display: boolean }

/** 相邻文本段合并，避免一段中文被拆成几十个单字节点。 */
function appendText(segments: Segment[], value: string): void {
  if (!value) return
  const last = segments.at(-1)
  if (last?.type === 'text') last.value += value
  else segments.push({ type: 'text', value })
}

/** 前面的反斜杠是奇数个，说明这个 `$` 被转义了。 */
function isEscaped(text: string, index: number): boolean {
  let slashes = 0
  for (let cursor = index - 1; cursor >= 0 && text[cursor] === '\\'; cursor--) slashes++
  return slashes % 2 === 1
}

function displayClose(text: string, from: number): number {
  for (let index = from; index < text.length - 1; index++) {
    if (text[index] === '$' && text[index + 1] === '$' && !isEscaped(text, index)) return index
  }
  return -1
}

/**
 * 行内公式的收尾 `$`。两个排除条件都是为了不和日常文本抢：
 * 前一个字符是空白（`$ x$`）说明是误输入的孤立美元符号；
 * 后一个字符是数字（`$5`）说明是价格。
 */
function inlineClose(text: string, from: number): number {
  for (let index = from; index < text.length; index++) {
    if (text[index] !== '$' || isEscaped(text, index)) continue
    if (/\s/.test(text[index - 1] ?? '') || /\d/.test(text[index + 1] ?? '')) continue
    return index
  }
  return -1
}

/**
 * 单趟扫描。识别不了的定界符一律退回文本——宁可让教师看到 `$`，
 * 也不要猜错边界后把半句话送进公式渲染器。
 */
export function splitMath(text: string): Segment[] {
  const segments: Segment[] = []
  let index = 0
  while (index < text.length) {
    if (text[index] === '\\' && text[index + 1] === '$') {
      appendText(segments, '$')
      index += 2
      continue
    }
    if (text[index] !== '$') {
      appendText(segments, text[index])
      index++
      continue
    }

    if (text[index + 1] === '$') {
      const close = displayClose(text, index + 2)
      if (close >= 0) {
        const value = text.slice(index + 2, close)
        if (value.trim()) {
          segments.push({ type: 'math', value, display: true })
          index = close + 2
          continue
        }
        appendText(segments, text.slice(index, close + 2))
        index = close + 2
        continue
      }
      appendText(segments, '$')
      index++
      continue
    }

    if (/\s/.test(text[index + 1] ?? '')) {
      appendText(segments, '$')
      index++
      continue
    }
    const close = inlineClose(text, index + 1)
    if (close >= 0) {
      segments.push({ type: 'math', value: text.slice(index + 1, close), display: false })
      index = close + 1
      continue
    }
    appendText(segments, '$')
    index++
  }
  return segments
}
