# 公式渲染设计方案（前端 KaTeX + Word Unicode 子集）

## 1. 背景与目标

题干、学生作答和标准答案目前按纯文本保存，可以包含 `$...$` 形式的 LaTeX，但全系统没有任何地方渲染它：教师在页面上看到的是 `$2x+1=5$` 这样的字符串，打印出来的 Word 练习单里也是同一串字符。

本方案要解决的是"公式看起来是对的"这一件事，分两层：

- **页面**：教师端 5 处数学文本用 KaTeX 渲染成公式；
- **打印稿**：Word 导出时把 LaTeX 转成一个有明确边界的 Unicode 子集，转不了的公式在导出时点名、在文档里标红。

不改变存储：题干和答案仍然以 UTF-8 纯文本保存、可以包含 LaTeX 分隔符、不保存用户 HTML（与第一阶段设计一致）。

## 2. 现状（实测结论，非假设）

### 2.1 真实语料

demo 种子数据里的公式全部落在本方案的子集内：`$2x+1=5$`、`$3(x-1)=9$`、`$(-2x)^2$`、`$2x^2y$`、`$(x+3)(x-3)$`、`$AB \parallel CD$`、`$\angle 1=65°$`、`$\angle 2=$`。

两条从语料里读出来、影响设计的事实：

1. **度符号直接写 `°`**，不是 `^\circ`，所以转换器不需要为角度额外造规则；
2. **标准答案里有不带 `$` 的裸上标**：`Q-ALG-007` 的标准答案是 `4x^2`，可接受答案也是 `4x^2`。因此 Word 转换器不能只处理 `$...$` 段。

### 2.2 KaTeX 对真实语料的行为

用 KaTeX 0.18.7 逐条验证：

| 输入 | 结果 |
| --- | --- |
| `2x+1=5`、`(-2x)^2`、`3(x-1)=9`、`x=2` | 正常 |
| `AB \parallel CD`、`\angle 1=65°`、`\angle 2=` | 正常，`°` 在数学模式下可渲染 |
| `\frac{3}{4}`、`\sqrt{2}`、`x^{2}`、`\triangle ABC`、`\odot O` | 正常 |
| `\begin{cases}x+y=3\\x-y=1\end{cases}` | **正常渲染** |
| `\frac{1}{2`（残缺） | `throwOnError: false` 下输出 `<span class="katex-error" style="color:#cc0000">` |
| 数学模式里的裸中文 `$解$` | 能渲染，但会向 console 打 strict 警告 |

两个直接推论：

- **页面能渲染不等于 Word 里能渲染。** `\begin{cases}` 在页面上是漂亮的方程组，却是 Word 子集大概率转不了的东西。这就是"导出时点名"存在的理由，不是保险措施。
- KaTeX 的输出里带 `<span class="katex-mathml">`（供读屏使用），**必须引入它的 CSS**，否则 KaTeX 的 `.katex-mathml` 隐藏规则缺失，每个公式会显示两遍。

### 2.3 体积

| 渲染库 | JS 未压缩 | JS gzip |
| --- | --- | --- |
| MathJax 4.1.3 `tex-chtml.js`（已在依赖里，从未 import） | 997 KB | 282 KB |
| KaTeX 0.18.7 | 273 KB | 76 KB |

KaTeX 另需 CSS 25 KB（gzip 3.6 KB）与按需取用的 woff2 字体。当前主包 163 kB。

### 2.4 现有代码的相关约束

- `frontend/src/router/index.ts` 的所有路由都是 `() => import(...)` 懒加载，引用 KaTeX 的三个视图都是懒加载路由，因此 KaTeX 会落进一个共享的异步 chunk，**主包体积不变**。
- `frontend/vite.config.ts` 的 `unplugin-vue-components` 未配置 `dirs`，默认扫描 `src/components`；`Components({ dts: 'src/components.d.ts' })` 会为该目录下的组件自动生成全局类型。
- `frontend/src/api/client.ts:93` 的 `downloadExerciseDocx` 已经用 axios 以 `responseType: 'blob'` 下载，并且已经在读响应头 `content-disposition`，再加读一个自定义响应头几乎零成本。
- `ExerciseDocumentExporter.styled(paragraph, fontSize, bold)` 返回 `XWPFRun`，调用方立刻 `.setText(...)`；标准答案与它的标签写在同一个段落的两条 run 里，评分细则则是单条 run 拼接。
- `frontend/vite.config.ts` 的 `test.css: true`，且 `node_modules` 依赖默认外部化后交给 Node 原生加载；element-plus 的 `theme-chalk/*.css` 已经因此被 alias 到空模块。

## 3. 前端渲染方式

在三个方案中选定方案 A。

**方案 A（选定）：分段渲染，只把 KaTeX 自己的输出写进 innerHTML。**

`splitMath` 把文本切成"文本段 / 公式段"，文本段交给 Vue 的文本插值（`{{ }}`，自动转义），公式段交给一个只做一件事的子组件，由它把 `katex.renderToString()` 的返回值写进自己的 `span`。

两个模块的对外形状：

```ts
// segments.ts —— 纯函数，无依赖，字符串进、数组出
type Segment =
  | { type: 'text'; value: string }
  | { type: 'math'; value: string; display: boolean }

function splitMath(text: string): Segment[]

// render.ts —— 全项目唯一 import katex 的地方
function renderTex(tex: string, display: boolean): string
```

关键性质：**用户文本永远不进入 innerHTML**。全项目仍然没有一处 `v-html`，也不需要自写 `escapeHtml`——整整一类转义缺陷被结构性地消除，而不是靠写对一个函数来避免。

**方案 B（未选）**：单个组件 + `v-html` + 自写 `escapeHtml`。模板最短，但转义的正确性需要自己论证，且 `v-html` 与"后端不渲染用户 HTML"的既有边界叙述冲突。

**方案 C（未选）**：KaTeX 官方 `auto-render` 命令式扫描 DOM。不需要 `v-html`，但必须在 `onMounted` 与 `watch` 里手动重跑，而 KaTeX 会往 Vue 管理的 DOM 里插入节点——复核页在队列里切换下一条答案时最容易出现新旧的公式打架；单测还必须挂载真实 DOM。

组件放在 `src/math/` 而不是 `src/components/`：后者会被按需导入插件自动注册为全局组件（模板里凭空可用，看不出依赖从哪来），且会写入 `src/components.d.ts`。`src/math/` 下各视图显式 import，依赖可见。

## 4. 定界符与降级约定

**前端渲染与后端 Word 转换共用这一套约定**，两层对 LaTeX 的理解必须一致，否则"页面上好好的、打印出来是乱码"就无法解释。

| 规则 | 行为 |
| --- | --- |
| `$$...$$` | 独立成行的公式（KaTeX display 模式） |
| `$...$` | 行内公式 |
| `\$` | 字面 `$`，不作为分隔符 |
| 未闭合的 `$` | 当普通文本（"价格是 $5" 不会被吃掉） |
| 空公式（`$$`、`$ $`） | 当普通文本 |
| 命令的识别范围 | **只在 `$...$` 内**。段外的 `\angle` 不解释，当普通文本 |
| 行内公式的额外约束 | 开 `$` 后不能紧跟空白；闭 `$` 前不能是空白；闭 `$` 后不能紧跟数字（防止"价格 $5 元，$8 元"被吃成公式）。`$$` 不受这三条限制 |
| 公式解析失败 | KaTeX 红色回退（`throwOnError: false`），不阻断页面 |

`$$` 的闭合查找失败时（如 `$$x$`），退化为把第一个 `$` 当字面字符、从下一个字符继续扫描。因此 `$$x$` 的确切结果是"一个字面 `$` 加一个行内公式 `x`"——任何输入都有确定结果，不会抛异常。

KaTeX 选项集中在一个常量里显式声明：

```ts
{ throwOnError: false, strict: false, output: 'htmlAndMathml' }
```

`strict: false` 必须显式设置，否则数学模式里的裸中文（`$解$`）每渲染一次就往 console 打一行 `LaTeX-incompatible input and strict mode is set to 'warn'`。

## 5. 前端应用位置

| 文件 | 字段 | 变化 |
| --- | --- | --- |
| `frontend/src/views/QuestionView.vue:35` | 题干 `item.content` | `<p>` 内的文本改为 `MathText` |
| `frontend/src/views/ExerciseView.vue:181` | 练习单题干 | 同上 |
| `frontend/src/views/ReviewView.vue:41` | 题干 `selected.questionContent` | 同上 |
| `frontend/src/views/ReviewView.vue:42` | 学生作答 `selected.answerContent` | 同上，空值仍显示"（未作答）" |
| `frontend/src/views/ReviewView.vue:43` | AI 建议说明 `selected.teacherExplanation` | 同上 |

不改变这五处的排版与字体类（`.formula-text` 保持现状），本轮只把文本渲染方式换掉。

录入表单（`el-input` 文本域）不渲染公式——输入时看到的是 LaTeX 源码，这是本轮的有意取舍，见第 11 节。

## 6. 后端：Word 的 Unicode 子集转换

新增 `backend/src/main/java/com/homework/analysis/exercise/LatexToUnicode.java`，放在唯一消费者 `ExerciseDocumentExporter` 旁边；出现第二个消费者时再搬到共用位置。

### 6.1 一条主规则

> **上标与下标全程转换，LaTeX 命令只在 `$...$` 段内解释。**

- 全程转上下标：`4x^2` → `4x²`。这条不是可选的，见 2.1 的第 2 条事实。
- 命令只在段内解释：段外的 `\angle` 原样保留，与前端一致。

### 6.2 命令表（明确列出的子集，不是通用转换器）

| 类别 | 映射 |
| --- | --- |
| 几何与关系 | `\angle`→∠、`\parallel`→∥、`\perp`→⊥、`\triangle`→△、`\odot`→⊙、`\sim`→∽、`\cong`→≌ |
| 运算符 | `\times`→×、`\div`→÷、`\pm`→±、`\cdot`→· |
| 比较 | `\leq`/`\le`→≤、`\geq`/`\ge`→≥、`\neq`/`\ne`→≠、`\approx`→≈ |
| 结构 | `\sqrt{x}`→√x（含 `\sqrt[3]{x}`→³√x）、`\frac{a}{b}`→a/b |
| 上标 | `^\circ`→° |
| 尺寸命令（删除） | `\left`、`\right` |
| 空白命令（转空格） | `\,`、`\;`、`\ ` |
| 转义 | `\{`→`{`、`\}`→`}`、`\%`→`%`、`\$`→`$`、`\&`→`&`、`\#`→`#`、`\_`→`_` |

### 6.3 分数的括号规则

`\frac{a}{b}` 转成 `a/b` 时，分子或分母只要**含低优先级运算符（`+`、`-`）或空格**，就必须补括号，否则会改变运算顺序：

| 输入 | 输出 | 说明 |
| --- | --- | --- |
| `\frac{2}{3}` | `2/3` | 都是单个原子，不补 |
| `\frac{\sqrt{2}}{2}` | `√2/2` | 不补 |
| `\frac{x+1}{2}` | `(x+1)/2` | 分子含 `+`，补 |
| `\frac{a+b}{c}` | `(a+b)/c` | 分子含 `+`，补；不补就成了 `a+b/c`，是另一个式子 |
| `\frac{a}{b-c}` | `a/(b-c)` | 分母含 `-`，补 |
| `\frac{-1}{2}` | `(-1)/2` | 分子以 `-` 开头，补 |

分子分母都含运算符时两边都补：`\frac{a+b}{c-d}` → `(a+b)/(c-d)`。

### 6.4 上下标映射

- 上标：`0-9`→`⁰¹²³⁴⁵⁶⁷⁸⁹`、`+`→`⁺`、`-`→`⁻`、`=`→`⁼`、`(`→`⁽`、`)`→`⁾`、`n`→`ⁿ`、`i`→`ⁱ`（Unicode 没有上标 `j`，所以 `^{j}` 会保持原样，这不是遗漏）
- 下标：`0-9`→`₀₁₂₃₄₅₆₇₈₉`、`+`→`₊`、`-`→`₋`、`=`→`₌`、`(`→`₍`、`)`→`₎`、`i`→`ᵢ`、`j`→`ⱼ`

规则：`^` 或 `_` 后面跟一个 `{...}` 组或单个字符；**组内每个字符都能映射才整组转换**（`^{2n}`→`²ⁿ`、`x_1`→`x₁`），否则保留 `^{ab}` 原样。保留原样仍然可读，**不算转换失败，不计入点名**。

**处理顺序：转义先于上下标。** `\_` 必须得到字面 `_`，不能再被当成下标标记（`\_i` 是"_i"两个字符，不是 `_ᵢ`）。同理 `\{`、`\}` 先于花括号分组处理。顺序写反会安静地产生错误输出，因此这一条要单独有用例。

### 6.5 失败判定

只有"段内出现命令表里没有的命令"（如 `\begin`、`\vec`、`\overline`）才算转换失败。失败时保留原文，并让该题的题号上报。子集之外一律走这条路，不做更聪明的近似。

### 6.6 应用字段

题干、标准答案、评分项标题、评分项得分标准。这四处正是 `ExerciseDocumentExporter` 写入数学文本的全部位置。

### 6.7 返回值

```java
public record Conversion(String text, boolean fellBack) {}
```

`Conversion` 是转换器的唯一出口，让"文本"和"是否失败"永远一起返回，避免调用方忘记收集失败信息。

## 7. 转换失败的上报与提示

一个失败要有两个去处：**教师得在导出那一刻知道**，**得知道是哪一道题**。

### 7.1 后端

- `ExerciseDocumentExporter.write(...)` 的返回类型由 `byte[]` 改为
  `ExportedDocument(byte[] content, List<String> fallbackQuestionCodes)`；`ExerciseService.exportDocx(...)` 同步改为返回 `ExportedDocument`。
- 导出器内部把"转换 + 标红 + 收集题号"收在一个私有方法里，四处写入点（题干、标准答案、评分项标题、评分项得分标准）统一调用它：

  ```java
  private static void writeText(XWPFParagraph paragraph, int fontSize, boolean bold,
                                String raw, Set<String> fallbackCodes, String questionCode)
  ```

  转换、失败标红、题号收集三件事只在这一个地方发生。同一个题号多次失败只记录一次。

- 失败的 run 用 `XWPFRun.setColor("CC0000")`，与 KaTeX 的 `katex-error` 同一种红，页面和打印稿的视觉约定一致。
- `ExerciseController.export` 增加响应头，值由题号逗号连接：

  ```text
  X-Formula-Fallback: Q-ALG-007,Q-GEO-005
  ```

  题号按出现顺序去重排列，最多 20 个；超过 20 个时截断到 20 个并在末尾追加一个 `...` 元素（此时共 21 项）。所有题号都是 ASCII（形如 `Q-ALG-007`），不存在响应头编码问题。没有失败时**不带这个响应头**。
- 导出照常成功，不因为公式转换失败而失败。

### 7.2 前端

- `downloadExerciseDocx` 的返回类型由 `Promise<void>` 改为 `Promise<string[]>`，读 `response.headers['x-formula-fallback']` 并拆成题号数组；没有该头时返回空数组。
- 提示由页面发出，不由 `api/client.ts` 发出——与本项目现有约定一致（`ElMessage` 由页面显式调用，测试才能 mock）。`ExerciseView.vue` 的 `download()` 在下载完成后，若题号数组非空，弹出：

  ```text
  这些题目的公式没能完整转成 Word 格式，文档中已标红：Q-ALG-007、Q-GEO-005
  ```

  数组里若含 `...` 元素，渲染成"，等"。

### 7.3 已知约束

响应头只在同源下可读。开发期前端经 Vite 代理访问 `/api`，属同源；若将来前端与后端跨域部署，需要在后端的 CORS 配置里加 `Access-Control-Expose-Headers: X-Formula-Fallback`。本轮不做，记录在此避免以后当成 bug 排查。

## 8. 模块与文件清单

### 8.1 前端新增

| 文件 | 职责 |
| --- | --- |
| `frontend/src/math/segments.ts` | 纯函数 `splitMath(text): Segment[]`，无任何依赖 |
| `frontend/src/math/render.ts` | 全项目唯一调用 KaTeX 的地方，集中 KaTeX 选项 |
| `frontend/src/math/MathText.vue` | 遍历 `splitMath` 的结果：文本段走插值，公式段交给子组件 |
| `frontend/src/math/MathSegment.vue` | 渲染单个公式：把 `renderTex()` 的结果写进自己的 `span` |
| `frontend/src/math/segments.test.ts` | 定界符边界 |
| `frontend/src/math/render.test.ts` | 真实语料与失败回退 |
| `frontend/src/math/MathText.test.ts` | 组件行为与 XSS 断言 |

### 8.2 前端修改

| 文件 | 变化 |
| --- | --- |
| `frontend/src/main.ts` | 引入 `katex/dist/katex.min.css` |
| `frontend/src/views/QuestionView.vue` | 题干改用 `MathText` |
| `frontend/src/views/ExerciseView.vue` | 练习单题干改用 `MathText`；下载后按题号提示 |
| `frontend/src/views/ReviewView.vue` | 题干、学生作答、AI 建议说明改用 `MathText` |
| `frontend/src/api/client.ts` | `downloadExerciseDocx` 返回题号数组 |
| `frontend/src/api/client.test.ts` | 新增"响应头带题号时返回值正确""无该头时返回空数组" |
| `frontend/package.json` | 增加 `katex`，删除 `mathjax` |
| `frontend/e2e/teacher-workflow.spec.ts` | 断言公式已渲染（存在 `.katex`，且原样字符串 `$2x+1=5$` 不再出现） |

KaTeX 的 CSS 放在 `main.ts` 而不是组件里，理由是**可测性**：组件里引 CSS 会让 vitest 也去加载它，而外部化的 `.css` 交给 Node 原生加载会报 `Unknown file extension`（即 `vite.config.ts` 里那段 alias 注释描述的老问题）。放在 `main.ts` 后，测试不加载 `main.ts`，因此不需要新增 alias。

### 8.3 后端新增

| 文件 | 职责 |
| --- | --- |
| `backend/src/main/java/com/homework/analysis/exercise/LatexToUnicode.java` | 子集转换器，返回 `Conversion` |
| `backend/src/main/java/com/homework/analysis/exercise/ExportedDocument.java` | `record ExportedDocument(byte[] content, List<String> fallbackQuestionCodes)` |
| `backend/src/test/java/com/homework/analysis/exercise/LatexToUnicodeTest.java` | 命令表逐条钉住 |

### 8.4 后端修改

| 文件 | 变化 |
| --- | --- |
| `ExerciseDocumentExporter.java` | 四处文本写入改走 `writeText`；返回 `ExportedDocument` |
| `ExerciseService.java` | `exportDocx` 返回 `ExportedDocument` |
| `ExerciseController.java` | 按返回的题号设置 `X-Formula-Fallback` 响应头 |
| `ExerciseDocumentExporterTest.java` | 断言文档里的公式已转换、失败处为红色 |
| `ExerciseServiceTest.java` / `ExerciseApiTest.java` | 跟随返回类型与响应头的调整 |

## 9. 测试计划

### 9.1 前端

`segments.test.ts`（每条规则一个用例）：

- `$x$` 切成公式段；`$$x$$` 切成的公式段 `display` 为真；`a$x$b` 切成三段；
- 未闭合：`价格是 $5` 全部是文本；
- 转义：`\$5` 是文本；
- 空公式：`$$`、`$ $` 是文本；
- 行内三条约束各一个用例：`$ x$`（开 `$` 后是空白）、`$x $`（闭 `$` 前是空白）、`价格 $5 元，$8 元`（闭 `$` 后紧跟数字）都不成公式，整串保持文本；
- 退化输入：`$$x$` 不抛异常且结果确定；
- 连续公式 `$a$$b$` 与中文夹公式 `解方程 $2x+1=5$，则 $x=$____。`

`render.test.ts`：

- 把 2.1 里 8 条真实公式逐条渲染，断言输出含 `katex`、不含 `katex-error`；
- 残缺 `\frac{1}{2` 断言含 `katex-error`（红色回退生效）；
- `\begin{cases}x+y=3\\x-y=1\end{cases}` 断言能渲染——这条同时是"页面能渲染不代表 Word 能渲染"的证据。

`MathText.test.ts`（`@vue/test-utils`）：

- 输入 `解方程 $2x+1=5$，则 $x=$____。` 时渲染出 `.katex`，且挂载后的文本内容里**不再出现 `$`**（这条断言只对"所有 `$` 都是分隔符"的输入成立，见 4 节：孤立 `$` 本就该原样显示）；
- **XSS**：输入 `<img src=x onerror=alert(1)>` 与 `$<script>alert(1)</script>$`，断言 DOM 里没有 `img`、没有 `script` 元素，且恶意字符串以文本形式可见；
- 文本变化时公式随之更新（复核页切换下一条的场景）。

### 9.2 后端

`LatexToUnicodeTest`：

- 命令表逐条（第 6.2 节每一行都有用例）；
- 上下标：`4x^2`→`4x²`、`x^{2n}`→`x²ⁿ`、`x_1`→`x₁`、`x^{ab}` 保持原样且不报失败；
- 段外命令不解释：`\angle 1=65°`（无 `$`）保持原样；
- 转义先于上下标：`\_i` 得到字面 `_i` 而不是 `_ᵢ`；
- 嵌套结构：`\frac{\sqrt{2}}{2}`→`√2/2`、`\sqrt[3]{x}`→`³√x`；
- 分数的括号规则逐条（第 6.3 节表格六行全部覆盖），其中 `\frac{a+b}{c}` 必须得 `(a+b)/c` 而不是 `a+b/c`；
- 失败：`\begin{cases}`、`\vec{a}` 触发 `fellBack`，且原文保留；
- 转换结果里**不残留 `$`**；
- 纯文本不受影响：`两直线平行，同位角相等。` 原样返回。

`ExerciseDocumentExporterTest`：

- 用真实体例的练习单导出，读回 docx（`XWPFDocument`），断言含 `∠1=65°` 而非 `\angle 1=65°`；断言 `4x^2` 已变成 `4x²`；
- 失败题目的 run 颜色为 `CC0000`，同段落里其他 run 颜色不变；
- 返回的 `fallbackQuestionCodes` 含且仅含失败题目，同一题多次失败只出现一次。

`ExerciseApiTest`：

- 导出接口的响应头 `X-Formula-Fallback` 内容正确；
- 全部落在子集内时**不返回**该响应头。

### 9.3 E2E

沿用现有 `teacher-workflow.spec.ts`：在题库页断言存在 `.katex` 元素，并断言题干里不再出现原样字符串 `$2x+1=5$`（不写成"卡片文本里不出现 `$`"——万一以后有题目正文里合法地含一个孤立 `$`，那种断言会误报）。

## 10. 依赖与体积

- 增加 `katex`（0.18.7）；删除 `mathjax`（已声明但全项目从未 import）。
- 引用 KaTeX 的三个视图都是懒加载路由，KaTeX 会落进它们共享的异步 chunk，**主包 163 kB 不变**；登录页与工作台不加载它。
- 构建后核对：`npm --prefix frontend run build` 无分包超限警告，主包体积与改动前一致。

## 11. 明确不做

- 录入表单里的实时公式预览、可视化公式编辑器（教师输入时看到的是 LaTeX 源码）；
- Word 原生公式（OMML）——Java 侧没有现成库，明确用 Unicode 子集替代；
- 在录入题目时用后端校验 LaTeX 是否合法（失败的处理推迟到导出并点名）；
- `$` 以外的 Markdown 语法（加粗、列表、表格等）；
- 对发给大模型的提示词做同样的转换（模型本来就吃 LaTeX）；
- 跨域部署下的 `Access-Control-Expose-Headers`（见 7.3）。

## 12. 文档同步

| 文件 | 变化 |
| --- | --- |
| `README.md:165` | "公式目前以安全纯文本保存，后端不渲染用户 HTML" → 存储仍是纯文本；页面用 KaTeX 渲染 `$...$`；Word 导出转 Unicode 子集，转不了的标红并在导出时点名 |
| `README.md` 已实现功能 | 增加一条公式渲染 |
| `docs/2026-09-14-项目阶段总结.md:143` | 划掉"LaTeX 公式仍按原文显示"这条待办 |
| `docs/superpowers/plans/2026-09-14-junior-math-homework-analysis-implementation.md:77` | 不改：存储约定（纯文本、不保存用户 HTML）依然成立 |

## 13. 验收方式

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend run test
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

浏览器验收（先启动 demo 后端与前端）：

1. 题库页：题干显示为公式；`Q-GEO-005` 的角度符号、`Q-ALG-007` 的平方正常；
2. 复核页：题干、学生作答、AI 建议说明三处都不再有 `$`；
3. 练习单页：题干正常，导出 Word 打开后标准答案区是 `∠1=65°` 与 `4x²`；
4. **失败路径**：临时录入一道题干含 `\begin{cases}...\end{cases}` 的解答题，加入练习单并导出，确认弹出点名提示、且 docx 里该处为红色原文。

第 4 步刻意不写进 demo 种子数据：现有种子数据全部落在子集内，加一道转不了的题会让默认导出的练习单每次都弹警告。失败路径由后端单测覆盖，浏览器验收时临时录入即可。
