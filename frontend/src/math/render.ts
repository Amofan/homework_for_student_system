/**
 * 全项目唯一的 KaTeX 适配器。把公式源码变成 HTML 字符串这件事只在这里发生，
 * 其它模块（包括组件）都通过 renderTex 取结果，这样渲染选项只有一处需要维护。
 */
import katex from 'katex'

const OPTIONS = {
  // 残缺公式不抛异常：教师录入 `\frac{1}{2` 时应当看到红色的错误提示而不是白屏。
  throwOnError: false,
  // 放宽严格模式，否则中文标点、`°` 之类的常见写法会被警告刷屏。
  strict: false,
  // 同时产出 MathML：屏幕阅读器与浏览器翻译功能依赖它。
  output: 'htmlAndMathml',
} as const

/** 返回 KaTeX 生成的 HTML。调用方负责把它放进 DOM（见 MathSegment.vue）。 */
export function renderTex(tex: string, display: boolean): string {
  return katex.renderToString(tex, { ...OPTIONS, displayMode: display })
}
