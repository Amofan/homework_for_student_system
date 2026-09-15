# LaTeX 公式渲染 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让教师端五处数学文本安全渲染 KaTeX，并让 Word 练习单把受支持的 LaTeX 转成 Unicode、对无法转换的题目标红并在下载时点名。

**Architecture:** 存储层继续保存纯文本，不保存用户 HTML。前端先用纯函数切分文本与公式，再由唯一的 KaTeX 适配器生成公式 DOM；后端导出时使用局部、确定性的 Unicode 子集转换器，并通过 `ExportedDocument` 和 `X-Formula-Fallback` 把降级题号传到页面。

**Tech Stack:** Vue 3.5、TypeScript 6、KaTeX 0.18.7、Vitest、Spring Boot 4.1.1、Java 21、Apache POI、JUnit 6、MockMvc、Playwright

**Spec:** `docs/superpowers/specs/2026-09-15-latex-formula-rendering-design.md`

## Global Constraints

- 题干、答案与评分项继续以 UTF-8 纯文本保存；不新增数据库迁移，不保存用户 HTML。
- 前端只识别 `$...$` 与 `$$...$$`；`\$`、未闭合、空公式和不满足空白/数字边界的输入按设计方案降级为文本。
- 用户文本段只能走 Vue 文本插值；全项目唯一的 `katex` import 位于 `frontend/src/math/render.ts`。
- KaTeX 固定使用 `{ throwOnError: false, strict: false, output: 'htmlAndMathml' }`，并全局引入它的 CSS。
- Word 只转换设计方案第 6 节列出的 Unicode 子集；未知命令保留公式源码、标红，并收集题号，不能让导出失败。
- `X-Formula-Fallback` 按题目出现顺序去重，最多返回 20 个题号；更多时追加 `...`。
- 不实现录入实时预览、OMML、Markdown、LaTeX 入库校验或跨域响应头暴露。
- 不顺带处理 favicon、复核错因下拉框、`OwnershipGuard` 死代码或 H2/Flyway 版本告警。

## Current Baseline

计划编写前于 2026-09-15 重新运行：

- `mvn -f backend/pom.xml test`：83 个用例通过；
- `npm --prefix frontend run test`：4 个文件、20 个用例通过；
- `npm --prefix frontend run typecheck`：通过；
- `npm --prefix frontend run build`：通过，主包 163.47 kB，无分包超限警告；
- `python -m unittest discover -s evaluation/tests -v`：20 个用例通过。

浏览器 E2E、MySQL Testcontainers 和真实模型本轮没有重跑；它们不是编写本计划的前置条件。实现完成时必须重跑浏览器主流程，公式改动没有数据库结构或模型契约变化，因此 MySQL 与真实模型验证不列为本计划门禁。

## File Structure

### Frontend additions

- `frontend/src/math/segments.ts`：只负责把原始文本切成 `text` / `math` 段。
- `frontend/src/math/render.ts`：唯一的 KaTeX 适配器与固定选项。
- `frontend/src/math/MathSegment.vue`：只把 KaTeX 生成的 HTML 放入自己的根节点。
- `frontend/src/math/MathText.vue`：组合文本段与公式段。
- `frontend/src/math/segments.test.ts`：定界符和降级规则。
- `frontend/src/math/render.test.ts`：真实语料、残缺公式和方程组。
- `frontend/src/math/MathText.test.ts`：响应式更新和 XSS 边界。

### Frontend modifications

- `frontend/package.json`、`frontend/package-lock.json`：用 KaTeX 0.18.7 替换未使用的 MathJax 4.1.3。
- `frontend/src/main.ts`：引入 KaTeX CSS。
- `frontend/src/views/QuestionView.vue`：题库题干改用 `MathText`。
- `frontend/src/views/ReviewView.vue`：题干、学生作答、AI 建议说明改用 `MathText`。
- `frontend/src/views/ExerciseView.vue`：练习题干改用 `MathText`；导出后展示降级题号。
- `frontend/src/views/ExerciseView.test.ts`：公式渲染和导出提示回归测试。
- `frontend/src/api/client.ts`、`frontend/src/api/client.test.ts`：下载函数返回响应头里的题号。
- `frontend/e2e/teacher-workflow.spec.ts`：在真实页面断言 KaTeX 已生效。

### Backend additions

- `backend/src/main/java/com/homework/analysis/exercise/LatexToUnicode.java`：共享定界符规则、Unicode 子集转换与失败标记。
- `backend/src/main/java/com/homework/analysis/exercise/ExportedDocument.java`：文档字节和降级题号的返回契约。
- `backend/src/test/java/com/homework/analysis/exercise/LatexToUnicodeTest.java`：转换器规则矩阵。

### Backend modifications

- `backend/src/main/java/com/homework/analysis/exercise/ExerciseDocumentExporter.java`：四类数学字段统一转换、标红、收集题号。
- `backend/src/main/java/com/homework/analysis/exercise/ExerciseService.java`：`exportDocx` 返回 `ExportedDocument`。
- `backend/src/main/java/com/homework/analysis/exercise/ExerciseController.java`：按需设置 `X-Formula-Fallback`。
- `backend/src/test/java/com/homework/analysis/exercise/ExerciseDocumentExporterTest.java`：文档文本、颜色和去重。
- `backend/src/test/java/com/homework/analysis/exercise/ExerciseServiceTest.java`：跟随返回契约。
- `backend/src/test/java/com/homework/analysis/exercise/ExerciseApiTest.java`：响应头存在与不存在两条路径。

### Documentation modifications

- `README.md`：记录页面和 Word 的真实公式能力。
- `docs/2026-09-14-项目阶段总结.md`：把公式原样显示从遗留问题改为已完成项，并记录本轮新验证。

---

### Task 1: Build the delimiter parser

**Files:**
- Create: `frontend/src/math/segments.ts`
- Create: `frontend/src/math/segments.test.ts`

**Interfaces:**
- Produces: `export type Segment = { type: 'text'; value: string } | { type: 'math'; value: string; display: boolean }`
- Produces: `export function splitMath(text: string): Segment[]`
- Consumes: no Vue, DOM, KaTeX, or project state.

- [ ] **Step 1: Write the delimiter behavior tests**

```ts
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
```

- [ ] **Step 2: Run the parser test and confirm the missing-module failure**

Run: `npm --prefix frontend run test -- src/math/segments.test.ts`

Expected: FAIL because `./segments` does not exist.

- [ ] **Step 3: Implement the single-pass parser**

```ts
export type Segment =
  | { type: 'text'; value: string }
  | { type: 'math'; value: string; display: boolean }

function appendText(segments: Segment[], value: string): void {
  if (!value) return
  const last = segments.at(-1)
  if (last?.type === 'text') last.value += value
  else segments.push({ type: 'text', value })
}

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

function inlineClose(text: string, from: number): number {
  for (let index = from; index < text.length; index++) {
    if (text[index] !== '$' || isEscaped(text, index)) continue
    if (/\s/.test(text[index - 1] ?? '') || /\d/.test(text[index + 1] ?? '')) continue
    return index
  }
  return -1
}

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
```

- [ ] **Step 4: Run the focused tests**

Run: `npm --prefix frontend run test -- src/math/segments.test.ts`

Expected: the parser test file passes.

- [ ] **Step 5: Commit the parser**

```powershell
git add frontend/src/math/segments.ts frontend/src/math/segments.test.ts
git commit -m "feat: add deterministic math delimiter parser"
```

### Task 2: Add the isolated KaTeX renderer

**Files:**
- Create: `frontend/src/math/render.ts`
- Create: `frontend/src/math/MathSegment.vue`
- Create: `frontend/src/math/MathText.vue`
- Create: `frontend/src/math/render.test.ts`
- Create: `frontend/src/math/MathText.test.ts`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

**Interfaces:**
- Consumes: `splitMath(text): Segment[]` from Task 1.
- Produces: `renderTex(tex: string, display: boolean): string`.
- Produces: `MathText` component with required `text: string` prop.
- Constraint: `MathSegment.vue` is the only component allowed to assign generated KaTeX HTML.

- [ ] **Step 1: Replace the unused dependency**

Run:

```powershell
npm --prefix frontend uninstall mathjax
npm --prefix frontend install katex@0.18.7 --save-exact
```

Expected: `package.json` contains `"katex": "0.18.7"`, contains no `mathjax`, and the lockfile follows it.

- [ ] **Step 2: Write renderer and component tests**

```ts
// frontend/src/math/render.test.ts
import { describe, expect, it } from 'vitest'
import { renderTex } from './render'

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
```

```ts
// frontend/src/math/MathText.test.ts
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
```

- [ ] **Step 3: Run the tests and confirm they fail before implementation**

Run: `npm --prefix frontend run test -- src/math/render.test.ts src/math/MathText.test.ts`

Expected: FAIL because `render.ts`, `MathText.vue`, and `MathSegment.vue` do not exist.

- [ ] **Step 4: Implement the KaTeX adapter and two components**

```ts
// frontend/src/math/render.ts
import katex from 'katex'

const OPTIONS = {
  throwOnError: false,
  strict: false,
  output: 'htmlAndMathml',
} as const

export function renderTex(tex: string, display: boolean): string {
  return katex.renderToString(tex, { ...OPTIONS, displayMode: display })
}
```

```vue
<!-- frontend/src/math/MathSegment.vue -->
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
```

```vue
<!-- frontend/src/math/MathText.vue -->
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
```

- [ ] **Step 5: Load the required KaTeX stylesheet from the application entry**

Add this import before `./styles.css` in `frontend/src/main.ts`:

```ts
import 'katex/dist/katex.min.css'
```

Do not import this CSS from the component; Vitest does not load `main.ts`, so the existing dependency-CSS alias remains sufficient.

- [ ] **Step 6: Run focused tests, type checking, and a build**

Run:

```powershell
npm --prefix frontend run test -- src/math/segments.test.ts src/math/render.test.ts src/math/MathText.test.ts
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Expected: all commands pass; build output contains a lazy KaTeX chunk, the main `index-*.js` stays approximately 163 kB, and no chunk-size warning appears.

- [ ] **Step 7: Commit the renderer**

```powershell
git add frontend/package.json frontend/package-lock.json frontend/src/main.ts frontend/src/math
git commit -m "feat: render safe math segments with katex"
```

### Task 3: Apply MathText to all five teacher-facing fields

**Files:**
- Modify: `frontend/src/views/QuestionView.vue`
- Modify: `frontend/src/views/ReviewView.vue`
- Modify: `frontend/src/views/ExerciseView.vue`
- Modify: `frontend/src/views/ExerciseView.test.ts`
- Modify: `frontend/e2e/teacher-workflow.spec.ts`

**Interfaces:**
- Consumes: `MathText` from Task 2.
- Produces: five rendered fields without changing request payloads, stored text, or existing layout classes.

- [ ] **Step 1: Change the existing exercise view test to require rendered math**

Replace the raw-source assertion:

```ts
expect(wrapper.text()).toContain('解方程 $2x+1=5$')
```

with:

```ts
expect(wrapper.find('.katex').exists()).toBe(true)
expect(wrapper.text()).not.toContain('$2x+1=5$')
```

- [ ] **Step 2: Add the browser assertion at the first formula-bearing page**

After login in `teacher-workflow.spec.ts`, visit the question bank and assert the specific demo formula:

```ts
await page.getByRole('link', { name: /数学题库/ }).click()
const equationCard = page.locator('.question-card').filter({ hasText: 'Q-ALG-001' })
await expect(equationCard.locator('.katex')).toBeVisible()
await expect(equationCard).not.toContainText('$2x+1=5$')
```

- [ ] **Step 3: Run the exercise view test and confirm it fails on raw text**

Run: `npm --prefix frontend run test -- src/views/ExerciseView.test.ts`

Expected: FAIL because `ExerciseView.vue` has not imported `MathText`.

- [ ] **Step 4: Import MathText explicitly in each lazy view**

Add to the script section of `QuestionView.vue`, `ReviewView.vue`, and `ExerciseView.vue`:

```ts
import MathText from '../math/MathText.vue'
```

- [ ] **Step 5: Replace exactly the five interpolation sites**

```vue
<!-- QuestionView.vue -->
<p class="formula-text"><MathText :text="item.content" /></p>

<!-- ExerciseView.vue -->
<p class="formula-text"><MathText :text="item.content" /></p>

<!-- ReviewView.vue -->
<p class="formula-text"><MathText :text="selected.questionContent" /></p>
<p><MathText :text="selected.answerContent || '（未作答）'" /></p>
<p><MathText :text="selected.teacherExplanation" /></p>
```

Do not change the LaTeX source shown in the question-entry `el-input`.

- [ ] **Step 6: Run the affected unit suite and static checks**

Run:

```powershell
npm --prefix frontend run test
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Expected: all frontend unit tests pass; `ExerciseView.test.ts` sees `.katex`; the build has no chunk-size warning.

- [ ] **Step 7: Commit the view integration**

```powershell
git add frontend/src/views/QuestionView.vue frontend/src/views/ReviewView.vue frontend/src/views/ExerciseView.vue frontend/src/views/ExerciseView.test.ts frontend/e2e/teacher-workflow.spec.ts
git commit -m "feat: render formulas across teacher workflows"
```

### Task 4: Implement the Word Unicode subset converter

**Files:**
- Create: `backend/src/main/java/com/homework/analysis/exercise/LatexToUnicode.java`
- Create: `backend/src/test/java/com/homework/analysis/exercise/LatexToUnicodeTest.java`

**Interfaces:**
- Produces: `static Conversion convert(String raw)`.
- Produces: nested `record Conversion(String text, boolean fellBack)`.
- Consumes: no Spring bean, database, POI, or external parser.

- [ ] **Step 1: Write parameterized tests for every supported rule**

```java
package com.homework.analysis.exercise;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatexToUnicodeTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        $\\angle 1=65°$|∠1=65°
        $AB \\parallel CD$|AB ∥ CD
        $a\\perp b$|a⊥ b
        $\\triangle ABC \\sim \\triangle DEF$|△ABC ∽ △DEF
        $\\odot O$|⊙O
        $a\\cong b$|a≌b
        $2\\times3\\div6$|2×3÷6
        $a\\pm b\\cdot c$|a± b· c
        $a\\leq b, c\\ge d, x\\ne y, m\\approx n$|a≤ b, c≥ d, x≠ y, m≈ n
        $\\left(x\\right)$|(x)
        $a\\,b\\;c\\ d$|a b c d
        $\\{x\\}\\%\\$\\&\\#\\_$|{x}%$&#_
        $^\\circ$|°
        """)
    void convertsSupportedCommands(String raw, String expected) {
        assertThat(LatexToUnicode.convert(raw))
            .isEqualTo(new LatexToUnicode.Conversion(expected, false));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        4x^2|4x²
        x^{2n}|x²ⁿ
        x_1|x₁
        x^{ab}|x^{ab}
        $\\frac{2}{3}$|2/3
        $\\frac{\\sqrt{2}}{2}$|√2/2
        $\\sqrt[3]{x}$|³√x
        $\\frac{x+1}{2}$|(x+1)/2
        $\\frac{a}{b-c}$|a/(b-c)
        $\\frac{a+b}{c-d}$|(a+b)/(c-d)
        $\\frac{-1}{2}$|(-1)/2
        """)
    void convertsStructuresAndScripts(String raw, String expected) {
        assertThat(LatexToUnicode.convert(raw).text()).isEqualTo(expected);
        assertThat(LatexToUnicode.convert(raw).fellBack()).isFalse();
    }

    @Test
    void leavesCommandsOutsideMathAndUnsupportedScriptsReadable() {
        assertThat(LatexToUnicode.convert("\\angle 1=65°").text()).isEqualTo("\\angle 1=65°");
        assertThat(LatexToUnicode.convert("$\\_i$").text()).isEqualTo("_i");
        assertThat(LatexToUnicode.convert("纯文本说明").text()).isEqualTo("纯文本说明");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        $\\begin{cases}x=1\\end{cases}$|\\begin{cases}x=1\\end{cases}
        $\\vec{a}$|\\vec{a}
        $\\overline{AB}$|\\overline{AB}
        """)
    void fallsBackOnUnknownCommandsWithoutLeavingDelimiters(String raw, String expected) {
        LatexToUnicode.Conversion result = LatexToUnicode.convert(raw);
        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.text()).doesNotContain("$");
        assertThat(result.fellBack()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test and confirm the missing-class failure**

Run: `mvn -f backend/pom.xml -Dtest=LatexToUnicodeTest test`

Expected: FAIL because `LatexToUnicode` does not exist.

- [ ] **Step 3: Implement the converter as a deterministic scanner**

Create the class with these exact boundaries:

```java
package com.homework.analysis.exercise;

import java.util.Map;
import java.util.Set;

final class LatexToUnicode {
    private static final Map<String, String> COMMANDS = Map.ofEntries(
        Map.entry("angle", "∠"), Map.entry("parallel", "∥"), Map.entry("perp", "⊥"),
        Map.entry("triangle", "△"), Map.entry("odot", "⊙"), Map.entry("sim", "∽"),
        Map.entry("cong", "≌"), Map.entry("times", "×"), Map.entry("div", "÷"),
        Map.entry("pm", "±"), Map.entry("cdot", "·"), Map.entry("leq", "≤"),
        Map.entry("le", "≤"), Map.entry("geq", "≥"), Map.entry("ge", "≥"),
        Map.entry("neq", "≠"), Map.entry("ne", "≠"), Map.entry("approx", "≈")
    );
    private static final Set<String> REMOVED = Set.of("left", "right");
    private static final Map<Character, Character> SUPERSCRIPTS = Map.ofEntries(
        Map.entry('0', '⁰'), Map.entry('1', '¹'), Map.entry('2', '²'), Map.entry('3', '³'),
        Map.entry('4', '⁴'), Map.entry('5', '⁵'), Map.entry('6', '⁶'), Map.entry('7', '⁷'),
        Map.entry('8', '⁸'), Map.entry('9', '⁹'), Map.entry('+', '⁺'), Map.entry('-', '⁻'),
        Map.entry('=', '⁼'), Map.entry('(', '⁽'), Map.entry(')', '⁾'),
        Map.entry('n', 'ⁿ'), Map.entry('i', 'ⁱ')
    );
    private static final Map<Character, Character> SUBSCRIPTS = Map.ofEntries(
        Map.entry('0', '₀'), Map.entry('1', '₁'), Map.entry('2', '₂'), Map.entry('3', '₃'),
        Map.entry('4', '₄'), Map.entry('5', '₅'), Map.entry('6', '₆'), Map.entry('7', '₇'),
        Map.entry('8', '₈'), Map.entry('9', '₉'), Map.entry('+', '₊'), Map.entry('-', '₋'),
        Map.entry('=', '₌'), Map.entry('(', '₍'), Map.entry(')', '₎'),
        Map.entry('i', 'ᵢ'), Map.entry('j', 'ⱼ')
    );

    private static final Set<String> PREFIX_COMMANDS = Set.of("angle", "triangle", "odot");

    private LatexToUnicode() {}

    static Conversion convert(String raw) {
        StringBuilder output = new StringBuilder();
        boolean fellBack = false;
        int plainStart = 0;
        int index = 0;
        while (index < raw.length()) {
            if (raw.charAt(index) == '\\' && index + 1 < raw.length() && raw.charAt(index + 1) == '$') {
                output.append(convertScripts(raw.substring(plainStart, index)));
                output.append('$');
                index += 2;
                plainStart = index;
                continue;
            }
            if (raw.charAt(index) != '$') {
                index++;
                continue;
            }

            if (index + 1 < raw.length() && raw.charAt(index + 1) == '$') {
                int close = displayClose(raw, index + 2);
                if (close >= 0 && !raw.substring(index + 2, close).trim().isEmpty()) {
                    output.append(convertScripts(raw.substring(plainStart, index)));
                    Conversion math = convertMath(raw.substring(index + 2, close));
                    output.append(math.text());
                    fellBack |= math.fellBack();
                    index = close + 2;
                    plainStart = index;
                    continue;
                }
                index = close >= 0 ? close + 2 : index + 1;
                continue;
            }

            if (index + 1 >= raw.length() || Character.isWhitespace(raw.charAt(index + 1))) {
                index++;
                continue;
            }
            int close = inlineClose(raw, index + 1);
            if (close >= 0) {
                output.append(convertScripts(raw.substring(plainStart, index)));
                Conversion math = convertMath(raw.substring(index + 1, close));
                output.append(math.text());
                fellBack |= math.fellBack();
                index = close + 1;
                plainStart = index;
                continue;
            }
            index++;
        }
        output.append(convertScripts(raw.substring(plainStart)));
        return new Conversion(output.toString(), fellBack);
    }

    private static int displayClose(String text, int from) {
        for (int index = from; index < text.length() - 1; index++) {
            if (text.charAt(index) == '$' && text.charAt(index + 1) == '$' && !isEscaped(text, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int inlineClose(String text, int from) {
        for (int index = from; index < text.length(); index++) {
            if (text.charAt(index) != '$' || isEscaped(text, index)) continue;
            if (Character.isWhitespace(text.charAt(index - 1))) continue;
            if (index + 1 < text.length() && Character.isDigit(text.charAt(index + 1))) continue;
            return index;
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) slashes++;
        return slashes % 2 == 1;
    }

    private static Conversion convertMath(String tex) {
        try {
            return new Conversion(new MathParser(tex).parse(), false);
        } catch (UnsupportedCommand exception) {
            return new Conversion(tex, true);
        }
    }

    private static String convertScripts(String text) {
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length() && text.charAt(index + 1) == '_') {
                output.append("\\_");
                index += 2;
                continue;
            }
            if (current != '^' && current != '_') {
                output.append(current);
                index++;
                continue;
            }
            Script script = scriptAt(text, index + 1);
            String mapped = mapScript(script.value(), current == '^' ? SUPERSCRIPTS : SUBSCRIPTS);
            if (mapped == null) {
                output.append(current);
                if (script.grouped()) output.append('{');
                output.append(script.value());
                if (script.grouped()) output.append('}');
            } else {
                output.append(mapped);
            }
            index = script.nextIndex();
        }
        return output.toString();
    }

    private static Script scriptAt(String text, int index) {
        if (index >= text.length()) return new Script("", false, index);
        if (text.charAt(index) != '{') {
            return new Script(String.valueOf(text.charAt(index)), false, index + 1);
        }
        Group group = groupAt(text, index);
        return group == null
            ? new Script(text.substring(index + 1), true, text.length())
            : new Script(group.value(), true, group.nextIndex());
    }

    private static String mapScript(String value, Map<Character, Character> mapping) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            Character mapped = mapping.get(value.charAt(index));
            if (mapped == null) return null;
            output.append(mapped);
        }
        return output.toString();
    }

    private static Group groupAt(String text, int openBrace) {
        if (openBrace >= text.length() || text.charAt(openBrace) != '{') return null;
        int depth = 0;
        for (int index = openBrace; index < text.length(); index++) {
            if (text.charAt(index) == '{') depth++;
            if (text.charAt(index) == '}' && --depth == 0) {
                return new Group(text.substring(openBrace + 1, index), index + 1);
            }
        }
        return null;
    }
    private static String fractionPart(String value) {
        return value.startsWith("-") || value.indexOf('+') >= 0 || value.indexOf('-') >= 0 || value.indexOf(' ') >= 0
            ? "(" + value + ")" : value;
    }

    record Conversion(String text, boolean fellBack) {}
    private record Group(String value, int nextIndex) {}
    private record Script(String value, boolean grouped, int nextIndex) {}

    private static final class UnsupportedCommand extends RuntimeException {}

    private static final class MathParser {
        private final String text;
        private final StringBuilder output = new StringBuilder();
        private int index;

        private MathParser(String text) {
            this.text = text;
        }

        private String parse() {
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current == '\\') {
                    command();
                } else if (current == '^' || current == '_') {
                    script(current);
                } else {
                    output.append(current);
                    index++;
                }
            }
            return output.toString();
        }

        private void command() {
            if (index + 1 >= text.length()) throw new UnsupportedCommand();
            char escaped = text.charAt(index + 1);
            if ("{}%$&#_".indexOf(escaped) >= 0) {
                output.append(escaped);
                index += 2;
                return;
            }
            if (escaped == ',' || escaped == ';' || escaped == ' ') {
                output.append(' ');
                index += 2;
                return;
            }

            int commandStart = index;
            index++;
            int nameStart = index;
            while (index < text.length() && Character.isLetter(text.charAt(index))) index++;
            if (nameStart == index) throw new UnsupportedCommand();
            String name = text.substring(nameStart, index);
            if ("frac".equals(name)) {
                fraction(commandStart);
                return;
            }
            if ("sqrt".equals(name)) {
                squareRoot(commandStart);
                return;
            }
            String mapped = COMMANDS.get(name);
            if (mapped != null) {
                output.append(mapped);
                if (PREFIX_COMMANDS.contains(name)
                        && index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                return;
            }
            if (REMOVED.contains(name)) return;
            throw new UnsupportedCommand();
        }

        private void fraction(int commandStart) {
            Group numerator = groupAt(text, index);
            if (numerator == null) {
                output.append(text, commandStart, index);
                return;
            }
            Group denominator = groupAt(text, numerator.nextIndex());
            if (denominator == null) {
                output.append(text, commandStart, numerator.nextIndex());
                index = numerator.nextIndex();
                return;
            }
            Conversion top = convertMath(numerator.value());
            Conversion bottom = convertMath(denominator.value());
            if (top.fellBack() || bottom.fellBack()) throw new UnsupportedCommand();
            output.append(fractionPart(top.text())).append('/').append(fractionPart(bottom.text()));
            index = denominator.nextIndex();
        }

        private void squareRoot(int commandStart) {
            String rootIndex = "";
            if (index < text.length() && text.charAt(index) == '[') {
                int close = text.indexOf(']', index + 1);
                if (close < 0) {
                    output.append(text, commandStart, index);
                    return;
                }
                rootIndex = text.substring(index + 1, close);
                index = close + 1;
            }
            Group radicand = groupAt(text, index);
            if (radicand == null) {
                output.append(text, commandStart, index);
                return;
            }
            Conversion converted = convertMath(radicand.value());
            if (converted.fellBack()) throw new UnsupportedCommand();
            String mappedIndex = mapScript(rootIndex, SUPERSCRIPTS);
            if (!rootIndex.isEmpty() && mappedIndex == null) {
                output.append(text, commandStart, radicand.nextIndex());
            } else {
                output.append(mappedIndex == null ? "" : mappedIndex).append('√').append(converted.text());
            }
            index = radicand.nextIndex();
        }

        private void script(char marker) {
            if (marker == '^' && text.startsWith("\\circ", index + 1)) {
                output.append('°');
                index += 6;
                return;
            }
            Script script = scriptAt(text, index + 1);
            String mapped = mapScript(script.value(), marker == '^' ? SUPERSCRIPTS : SUBSCRIPTS);
            if (mapped == null) {
                output.append(marker);
                if (script.grouped()) output.append('{');
                output.append(script.value());
                if (script.grouped()) output.append('}');
            } else {
                output.append(mapped);
            }
            index = script.nextIndex();
        }
    }
}
```

The code above deliberately uses this fixed order rather than regular-expression replacement:

1. delimiter scan using the same `isEscaped`, inline-boundary, empty-formula, and `$$x$` fallback decisions as Task 1;
2. inside math, consume escaped literals (`\{`, `\}`, `\%`, `\$`, `\&`, `\#`, `\_`) before script handling;
3. parse `\frac{...}{...}` and `\sqrt[...]{...}` with balanced groups and recursively convert their contents;
4. map simple commands, remove `\left` / `\right`, and normalize spacing commands;
5. if an alphabetic command is not in the tables, return the original formula body with `fellBack=true`;
6. convert scripts only when every character in the group has a Unicode mapping; otherwise retain the original marker and group.

Do not approximate `\begin`, `\vec`, `\overline`, or any command outside the explicit table.

- [ ] **Step 4: Run focused and exercise-module tests**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=LatexToUnicodeTest test
mvn -f backend/pom.xml "-Dtest=ExerciseDocumentExporterTest,ExerciseServiceTest,ExerciseApiTest" test
```

Expected: the converter matrix passes and existing exercise tests remain green before their return types are changed.

- [ ] **Step 5: Commit the converter**

```powershell
git add backend/src/main/java/com/homework/analysis/exercise/LatexToUnicode.java backend/src/test/java/com/homework/analysis/exercise/LatexToUnicodeTest.java
git commit -m "feat: convert supported latex for word export"
```

### Task 5: Propagate Word conversion fallbacks through the backend

**Files:**
- Create: `backend/src/main/java/com/homework/analysis/exercise/ExportedDocument.java`
- Modify: `backend/src/main/java/com/homework/analysis/exercise/ExerciseDocumentExporter.java`
- Modify: `backend/src/main/java/com/homework/analysis/exercise/ExerciseService.java`
- Modify: `backend/src/main/java/com/homework/analysis/exercise/ExerciseController.java`
- Modify: `backend/src/test/java/com/homework/analysis/exercise/ExerciseDocumentExporterTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/exercise/ExerciseServiceTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/exercise/ExerciseApiTest.java`

**Interfaces:**
- Consumes: `LatexToUnicode.convert(raw)` from Task 4.
- Produces: `public record ExportedDocument(byte[] content, List<String> fallbackQuestionCodes)`.
- Produces: `ExerciseService.exportDocx(long teacherId, long exerciseId): ExportedDocument`.
- Produces: optional HTTP header `X-Formula-Fallback`.

- [ ] **Step 1: Extend exporter tests with converted and failed formulas**

Change the sample data to include:

```java
new ExerciseItemView(ExerciseTier.FOUNDATION, 1, 401, "Q-ALG-007",
    "计算 $2x^2y$", 5, QuestionDifficulty.BASIC, 301, "整式乘法", "4x^2",
    List.of(new RubricView(1, 1, "写出 $\\frac{a+b}{c}$", "不得使用 $\\vec{a}$", 4))),
new ExerciseItemView(ExerciseTier.CORRECTION, 1, 402, "Q-GEO-005",
    "已知 $\\angle 1=65°$，$AB \\parallel CD$", 5, QuestionDifficulty.MEDIUM,
    302, "平行线", "$\\angle 2=65°$", List.of())
```

Add assertions after reopening the DOCX:

```java
ExportedDocument exported = exporter.write(sample());
assertThat(paragraphs(exported.content()))
    .anyMatch(text -> text.contains("2x²y"))
    .anyMatch(text -> text.contains("标准答案：4x²"))
    .anyMatch(text -> text.contains("∠1=65°"))
    .anyMatch(text -> text.contains("(a+b)/c"));
assertThat(exported.fallbackQuestionCodes()).containsExactly("Q-ALG-007");

try (XWPFDocument opened = open(exported.content())) {
    assertThat(opened.getParagraphs().stream()
        .flatMap(paragraph -> paragraph.getRuns().stream())
        .filter(run -> run.text().contains("\\vec{a}"))
        .map(XWPFRun::getColor))
        .containsExactly("CC0000");
}
```

Add a second sample where all formulas are supported and assert `fallbackQuestionCodes()` is empty.

- [ ] **Step 2: Extend API tests for response-header presence and absence**

For a generated, approved exercise whose question content is updated to an unsupported formula:

```java
jdbc.update("update question set content = ? where id = 401", "$\\vec{a}$");

mvc.perform(get("/api/exercises/" + exerciseId + "/export.docx")
        .header("Authorization", bearer(11)))
    .andExpect(status().isOk())
    .andExpect(header().string("X-Formula-Fallback", "Q-401"));
```

On the existing supported-content export path add:

```java
.andExpect(header().doesNotExist("X-Formula-Fallback"))
```

Add one package-level helper test with 21 codes. Current business rules cap a practice sheet at 15 questions, so constructing an impossible 21-item exercise through the database would obscure the header rule:

```java
List<String> codes = IntStream.rangeClosed(1, 21).mapToObj(index -> "Q-" + index).toList();
assertThat(ExerciseController.fallbackHeader(codes))
    .isEqualTo("Q-1,Q-2,Q-3,Q-4,Q-5,Q-6,Q-7,Q-8,Q-9,Q-10,"
        + "Q-11,Q-12,Q-13,Q-14,Q-15,Q-16,Q-17,Q-18,Q-19,Q-20,...");
```

- [ ] **Step 3: Run the changed tests and confirm contract failures**

Run: `mvn -f backend/pom.xml "-Dtest=ExerciseDocumentExporterTest,ExerciseServiceTest,ExerciseApiTest" test`

Expected: FAIL because `write` and `exportDocx` still return `byte[]`, and the header is absent.

- [ ] **Step 4: Add the exported-document record**

```java
package com.homework.analysis.exercise;

import java.util.List;

public record ExportedDocument(byte[] content, List<String> fallbackQuestionCodes) {
    public ExportedDocument {
        content = content.clone();
        fallbackQuestionCodes = List.copyOf(fallbackQuestionCodes);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
```

- [ ] **Step 5: Route all four mathematical fields through one exporter helper**

In `ExerciseDocumentExporter`:

```java
private static void writeText(XWPFParagraph paragraph, int fontSize, boolean bold, String raw,
                              Set<String> fallbackCodes, String questionCode) {
    LatexToUnicode.Conversion conversion = LatexToUnicode.convert(raw == null ? "" : raw);
    XWPFRun run = styled(paragraph, fontSize, bold);
    run.setText(conversion.text());
    if (conversion.fellBack()) {
        run.setColor("CC0000");
        fallbackCodes.add(questionCode);
    }
}
```

At the start of `write`, create `LinkedHashSet<String> fallbackCodes`. Pass it through `writeItems` and `writeTeacherReference`, then return:

```java
return new ExportedDocument(output.toByteArray(), List.copyOf(fallbackCodes));
```

Split rubric lines into five runs so only mathematical user fields turn red:

```java
XWPFParagraph paragraph = document.createParagraph();
styled(paragraph, 11, false).setText("　　" + rubric.orderNo() + ". ");
writeText(paragraph, 11, false, rubric.title(), fallbackCodes, item.questionCode());
styled(paragraph, 11, false).setText("（" + rubric.maxScore() + " 分）：");
writeText(paragraph, 11, false, rubric.criteria(), fallbackCodes, item.questionCode());
```

Apply the same helper to item content and standard answer. Keep title, class metadata, question code, score, knowledge-point name, and fixed Chinese labels on ordinary runs.

- [ ] **Step 6: Change service and controller return contracts**

In `ExerciseService`:

```java
public ExportedDocument exportDocx(long teacherId, long exerciseId) {
    ExerciseSetView exercise = requireOwned(teacherId, exerciseId);
    if (exercise.status() != ExerciseStatus.APPROVED) {
        throw new DomainException("EXERCISE_NOT_APPROVED", "练习单确认后才能导出 Word", HttpStatus.CONFLICT);
    }
    return exporter.write(exercise);
}
```

In `ExerciseController`, build the optional header without adding it on the success-only path:

```java
private static final String FORMULA_FALLBACK = "X-Formula-Fallback";

ResponseEntity<byte[]> export(long exerciseId, Authentication authentication) {
    ExportedDocument document = service.exportDocx(currentTeacher.id(authentication), exerciseId);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
        .contentType(DOCX)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"exercise-" + exerciseId + ".docx\"");
    String fallback = fallbackHeader(document.fallbackQuestionCodes());
    if (!fallback.isEmpty()) response.header(FORMULA_FALLBACK, fallback);
    return response.body(document.content());
}

static String fallbackHeader(List<String> codes) {
    if (codes.isEmpty()) return "";
    List<String> visible = codes.stream().limit(20).toList();
    String joined = String.join(",", visible);
    return codes.size() > 20 ? joined + ",..." : joined;
}
```

- [ ] **Step 7: Update old byte-array assertions and run backend verification**

Change `service.exportDocx(...).isNotEmpty()` style assertions to inspect `.content()`. Change exporter helper calls from `paragraphs(exporter.write(sample()))` to `paragraphs(exporter.write(sample()).content())`.

Run:

```powershell
mvn -f backend/pom.xml "-Dtest=LatexToUnicodeTest,ExerciseDocumentExporterTest,ExerciseServiceTest,ExerciseApiTest" test
mvn -f backend/pom.xml test
```

Expected: focused tests and all backend tests pass; supported exports omit the header; failed formulas remain in red text and return ordered, deduplicated codes.

- [ ] **Step 8: Commit the backend export contract**

```powershell
git add backend/src/main/java/com/homework/analysis/exercise backend/src/test/java/com/homework/analysis/exercise
git commit -m "feat: report word formula conversion fallbacks"
```

### Task 6: Surface Word fallback codes in the download workflow

**Files:**
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/api/client.test.ts`
- Modify: `frontend/src/views/ExerciseView.vue`
- Modify: `frontend/src/views/ExerciseView.test.ts`

**Interfaces:**
- Consumes: optional `X-Formula-Fallback` from Task 5.
- Produces: `downloadExerciseDocx(exerciseId: number): Promise<string[]>`.
- Produces: a Chinese warning naming affected question codes.

- [ ] **Step 1: Add client tests for header parsing**

Make the existing download adapter return a header and assert the return value:

```ts
headers: {
  'content-disposition': 'attachment; filename="exercise-7.docx"',
  'x-formula-fallback': 'Q-ALG-007,Q-GEO-005,...',
},
```

```ts
await expect(downloadExerciseDocx(7)).resolves.toEqual(['Q-ALG-007', 'Q-GEO-005', '...'])
```

Add an adapter response without `x-formula-fallback`:

```ts
await expect(downloadExerciseDocx(8)).resolves.toEqual([])
```

- [ ] **Step 2: Add page tests for the warning text**

```ts
it('导出存在公式降级时点名题目并说明文档已标红', async () => {
  mocks.listExercises.mockResolvedValue([exercise({ status: 'APPROVED' })])
  mocks.downloadExerciseDocx.mockResolvedValue(['Q-ALG-007', 'Q-GEO-005', '...'])
  const wrapper = await mountView()

  await buttonWith(wrapper, '导出 Word')!.trigger('click')
  await flushPromises()

  expect(mocks.messages.warning).toHaveBeenCalledWith(
    '这些题目的公式没能完整转成 Word 格式，文档中已标红：Q-ALG-007、Q-GEO-005，等',
  )
})
```

Update the existing success-path mock from `mockResolvedValue(undefined)` to `mockResolvedValue([])`.

- [ ] **Step 3: Run focused tests and confirm they fail**

Run:

```powershell
npm --prefix frontend run test -- src/api/client.test.ts src/views/ExerciseView.test.ts
```

Expected: FAIL because the download helper returns no array and the page emits no warning.

- [ ] **Step 4: Return normalized fallback codes from the client**

Change the function signature and append this return after the existing `finally` block:

```ts
export async function downloadExerciseDocx(exerciseId: number): Promise<string[]> {
  // existing authorized blob download remains unchanged
  const fallback = response.headers['x-formula-fallback']
  return typeof fallback === 'string'
    ? fallback.split(',').map(code => code.trim()).filter(Boolean)
    : []
}
```

Keep the Blob URL revocation in `finally`; parsing the header must not bypass cleanup.

- [ ] **Step 5: Show the page-owned warning**

Change `ExerciseView.vue`:

```ts
const fallbackCodes = await downloadExerciseDocx(current.value.id)
ElMessage.success('已开始下载 Word 文档')
if (fallbackCodes.length) {
  const named = fallbackCodes.filter(code => code !== '...')
  const suffix = fallbackCodes.includes('...') ? '，等' : ''
  ElMessage.warning(
    `这些题目的公式没能完整转成 Word 格式，文档中已标红：\${named.join('、')}\${suffix}`,
  )
}
```

- [ ] **Step 6: Run the frontend suite and build**

Run:

```powershell
npm --prefix frontend run test
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Expected: all tests and static checks pass; no chunk-size warning; main bundle remains approximately 163 kB.

- [ ] **Step 7: Commit the download warning**

```powershell
git add frontend/src/api/client.ts frontend/src/api/client.test.ts frontend/src/views/ExerciseView.vue frontend/src/views/ExerciseView.test.ts
git commit -m "feat: warn on word formula fallbacks"
```

### Task 7: Complete browser acceptance and synchronize documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/2026-09-14-项目阶段总结.md`
- Modify if browser selectors need stabilization: `frontend/e2e/teacher-workflow.spec.ts`

**Interfaces:**
- Consumes: all tasks above.
- Produces: current documentation and fresh full verification evidence.

- [ ] **Step 1: Update the README capability and boundary statements**

Add to “已实现功能”:

```markdown
- 教师端题干、作答和评分说明的 KaTeX 公式渲染，以及 Word 导出的 Unicode 公式子集与降级题号提示；
```

Replace the final formula boundary with:

```markdown
公式仍以安全纯文本保存，不保存用户 HTML；教师端只把 `$...$` / `$$...$$` 公式段交给 KaTeX 渲染。Word 导出使用明确的 Unicode 子集，无法转换的公式保留源码并标红，同时在下载时点名题号。
```

- [ ] **Step 2: Update the stage summary using only fresh results**

In `docs/2026-09-14-项目阶段总结.md`:

- remove “LaTeX 公式仍按原文显示” from remaining issues;
- append a dated formula-rendering section describing the five front-end fields, Unicode subset, fallback header, warning behavior, dependency change, and commands actually run;
- keep favicon、错因自由文本、`OwnershipGuard`、H2/Flyway warning、真实匿名论文样本 as open limitations.

- [ ] **Step 3: Run the complete automated matrix**

Run:

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend run test
npm --prefix frontend run typecheck
npm --prefix frontend run build
python -m unittest discover -s evaluation/tests -v
```

Expected: all commands exit 0; record exact backend and frontend test counts and final bundle sizes in the stage summary.

- [ ] **Step 4: Start demo services for browser acceptance**

Terminal 1:

```powershell
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Terminal 2:

```powershell
npm --prefix frontend run dev
```

Wait for `http://localhost:8080/actuator/health` and `http://localhost:5173` to respond before continuing.

- [ ] **Step 5: Run the main Playwright workflow**

Run: `npm --prefix frontend run test:e2e`

Expected: login → question formula assertion → review → analytics → exercise generation → approval → non-empty DOCX download all pass.

- [ ] **Step 6: Perform the explicit Word fallback acceptance**

Using the demo account:

1. create a solution question whose content is `$\\begin{cases}x+y=3\\\\x-y=1\\end{cases}$`, total score is 5, and its single rubric item is worth 5;
2. attach it to a disposable demo assignment through the existing workflow, produce confirmed analysis, generate and approve a practice sheet containing it;
3. export Word and verify the warning names its question code;
4. open the DOCX and verify the preserved `\begin{cases}...\end{cases}` source is red;
5. do not add this unsupported formula to `DemoDataInitializer`.

This is the only manual failure-path check. Backend tests remain the repeatable source of truth for exact coloring and header truncation.

- [ ] **Step 7: Review the final diff and workspace**

Run:

```powershell
git diff --check
git diff --stat
git status --short
rg -n "mathjax|from 'katex'|from \\"katex\\"" frontend/src frontend/package.json
```

Expected:

- `git diff --check` is clean;
- `mathjax` has no package or source references;
- exactly one source file imports `katex`: `frontend/src/math/render.ts`;
- changes are limited to formula rendering, download fallback reporting, tests, dependencies, and documentation.

- [ ] **Step 8: Commit documentation and final verification**

```powershell
git add README.md docs/2026-09-14-项目阶段总结.md frontend/e2e/teacher-workflow.spec.ts
git commit -m "docs: record formula rendering verification"
```

## Plan Self-Review

- **Spec coverage:** sections 3–5 map to Tasks 1–3; sections 6–7 map to Tasks 4–6; dependency, documentation, automated verification, E2E, and manual fallback acceptance map to Tasks 2 and 7.
- **Scope control:** no database migration, AI contract change, live editor preview, OMML, CORS change, or unrelated cleanup is included.
- **Type consistency:** `ExportedDocument` is returned by exporter and service; controller consumes it. `downloadExerciseDocx` returns `Promise<string[]>`; `ExerciseView` and tests consume the same type.
- **Security boundary:** plain text uses Vue interpolation; only KaTeX output is assigned to `innerHTML`; unsupported Word formulas remain readable and are never executed.
- **Fresh baseline:** the plan records only commands run on 2026-09-15 and clearly separates tests not rerun.
