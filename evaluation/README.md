# 论文离线评测

按匿名评分样本计算评分误差与错因分类指标，产出可被论文直接引用的 JSON。

## 为什么要单独一套离线评测

系统在线的画像只统计**教师已确认**的结果，那是教学口径。论文需要的是另一回事：
模型建议与教师最终判定之间的偏差有多大、错因分类准不准、教师省了多少时间。
这两套口径不能混在一个查询里，否则改一次线上统计就会悄悄改变论文数字。
所以评测独立成脚本，输入是一份冻结的匿名 CSV——同一份输入永远得到同一份结果。

## 输入格式

### 评分样本 `grading_cases.example.csv`

```text
case_id,total_score,teacher_score,ai_score,teacher_error_type,ai_error_type,teacher_modified,teacher_seconds,ai_seconds,input_tokens,output_tokens,review_decision,model_name,prompt_version
```

| 列 | 必填 | 说明 |
|---|---|---|
| `case_id` | 是 | 匿名样本编号，如 `CASE-001`，不含学生身份信息 |
| `total_score` | 是 | 该题满分 |
| `teacher_score` | 是 | 教师最终判定分数（不是模型建议分） |
| `ai_score` | 是 | 模型建议分数 |
| `teacher_error_type` | 是 | 教师最终错因标签，即分类任务的**真值** |
| `ai_error_type` | 是 | 模型建议错因标签 |
| `teacher_modified` | 是 | 教师是否改动了模型建议，接受 `true/false`、`1/0`、`是/否` |
| `teacher_seconds` | 否 | 教师人工评分用时（秒） |
| `ai_seconds` | 否 | 模型评分用时（秒） |
| `input_tokens` | 否 | 输入令牌数，整数 |
| `output_tokens` | 否 | 输出令牌数，整数 |
| `review_decision` | 是 | 教师复核决策：`ACCEPT`、`MODIFY` 或 `REJECT` |
| `model_name` | 否 | 产出该建议的模型名，用于交代模型溯源 |
| `prompt_version` | 否 | 提示词版本，用于交代实验批次 |

可选列留空表示**缺失**，不是 0——空单元格不会被算进平均值，也不会把令牌总量拉低。
整列都空时，输出里对应字段为 `null`，而不是 `0`，避免被读成"没有消耗"。

`review_decision` 与 `teacher_modified` 说的是同一件事，读取时会交叉校验：
`teacher_modified` 必须等于 `review_decision != "ACCEPT"`，不一致直接报错。
保留旧的布尔列是因为论文前几节的数字按它统计，删掉会让新旧结果失去可比性；
但只留布尔值会把"驳回"和"普通修改"折叠成一类，而这两者的教学含义并不相同。

`model_name` 与 `prompt_version` 是溯源字段。`prompt_version` 在建表时有默认值 `'v1'`，
正常不会缺；`model_name` 会在模型任务行不存在时为空。缺多少条会记在
`provenance.missing_*_count`，缺失时留空，**不会**用某个模型名顶上。

### 错因标签表 `error_labels.example.csv`

```text
error_type,label_zh
```

固定参与统计的标签全集。不传 `--labels` 时改用样本里实际出现过的标签。
两者取并集：表里有但样本没出现的类别会以零样本出现在结果里，
样本里出现但不在表里的标签会进 `unknown_labels` 并在命令行给出警告，不会被静默丢掉。

标签编码与后端 `ErrorType` 一致：模型能用的六项，另有规则评分产生的
`ANSWER_MISMATCH`（客观题答错，模型不用这个标签）。
**改动后端标签时必须同步这张表**，否则分类指标会悄悄算错。

### 脱敏要求

输入必须已经脱敏：只有匿名编号，没有姓名、学号、班级、学校。
本脚本不做脱敏，也不应被当作脱敏工具——它假定上游已经处理干净，
这样脚本自身就不接触任何学生身份信息。

## 样本从哪来

不必手抄。后端提供导出接口，把一次作业里**教师已复核**的模型评分样本直接写成上面这份 CSV：

```text
GET /api/evaluation/assignments/{assignmentId}/grading-cases.csv
```

带教师身份的 Bearer 令牌调用，浏览器会直接下载 `grading-cases-{id}.csv`。
导出物本身就是文档，不带统一响应信封；列名、单位与上表完全一致，可以原样喂给脚本。

导出规则：

- 只导出**来源为模型**（`source = 'AI'`）且**已复核**（`teacher_review` 有记录）的样本。
  规则评分没有模型建议，待复核结果没有教师真值，两者都构不成一对可比较的值。
- `case_id` 用 `answer-{答案主键}`，导出物不含姓名、学号、班级。
- 教师用时、模型用时与令牌数取自各自的原始记录；前端没能计时、模型没有返回用量时留空。
- **缺少模型原始错因（`ai_error_type`）的样本会被剔除**，并在服务端日志里记一条 WARN 说明剔了几条。
  这类样本生成于本次迁移之前，教师复核已经覆盖了 `error_type`，模型当初判成什么无法还原；
  评测脚本要求该列非空，与其编造一个标签，不如剔除并留下痕迹。

### 导出完整性：三个响应头

**CSV 文件本身看不出少了行**，所以导出响应带三个计数头。做正式实验时逐份抄进运行日志：

| 响应头 | 含义 |
|---|---|
| `X-Evaluation-Reviewed` | 该作业中教师已复核的样本总数 |
| `X-Evaluation-Exported` | 实际写进 CSV 的行数 |
| `X-Evaluation-Skipped-Missing-Ai-Error` | 因缺少模型原始错因被剔除的行数 |

浏览器端能读到它们，靠的是响应上的 `Access-Control-Expose-Headers`——
**反向代理可能吃掉自定义响应头**，所以前端读到缺失时按 0 处理而不是 `NaN`，
页面上也会照实提示。三个数都没有时别默认"全导出了"，回去核对服务端日志。

只记实际导出条数，就会把导出量当成样本量；剔除的条数不落在日志里，占位痕迹就丢了。

导出后**仍需人工脱敏检查**：接口不输出身份信息，但把文件带出受控环境前请再确认一遍。

## 运行（冒烟）

拿仓库里的示例数据跑一遍，确认脚本能跑通、口径符合预期：

```powershell
python evaluation/evaluate_grading.py `
  --grading evaluation/data/grading_cases.example.csv `
  --labels evaluation/data/error_labels.example.csv `
  --output evaluation/output/metrics.json
```

参数：

- `--grading`：评分样本 CSV，必填；
- `--labels`：错因标签表，可选；
- `--output`：结果 JSON 路径，必填，父目录会自动创建；
- `--tolerance`：容差分数，默认 1。

示例数据是用来说明口径的，条数很少，**不是实验结果**，不要把这些数字写进论文。

跑测试：

```powershell
python -m unittest discover -s evaluation/tests -v
```

脚本只用 Python 标准库，评测可在离线环境重跑，不需要联网也不需要安装依赖。

## 运行（正式实验）

正式实验的口径已经冻结，见 `docs/experiments/2026-09-15-论文正式实验方案.md`：
研究问题、样本纳入排除规则、人工评分流程、指标口径四项按方案执行。
样本与结果归档在**仓库之外**，`evaluation/output/` 只是临时目录。

```powershell
$experimentRoot = 'E:\homework-analysis-experiment-archive\2026-09-main-study'

python evaluation/evaluate_grading.py `
  --grading "$experimentRoot\input\grading-cases.csv" `
  --labels  "$experimentRoot\input\error-labels.csv" `
  --output  "$experimentRoot\output\metrics.json"
```

正式实验里 `--labels` 必须显式传。不传时会退化成"样本里实际出现过的标签"，
换一份样本标签集合就变了，宏平均 F1 的分母随之改变，两批数据就不可比。
`input\error-labels.csv` 是 `evaluation/data/error_labels.example.csv` 的冻结副本——
它随后端 `ErrorType` 一起维护，所以实验期间不要换后端版本。

三个文件的 SHA-256 都要算并存进 `checksums\sha256.txt`：

```powershell
Get-FileHash "$experimentRoot\input\grading-cases.csv" -Algorithm SHA256
Get-FileHash "$experimentRoot\input\error-labels.csv"  -Algorithm SHA256
Get-FileHash "$experimentRoot\output\metrics.json"     -Algorithm SHA256
```

哈希是"论文引用的到底是哪一份输入"的唯一凭据：输入改了，
指标就不再是同一批数据算出来的。归档布局与日志内容要求见方案的"归档布局"一节。

## 输出指标

```json
{
  "sample_count": 8,
  "score": {
    "mean_absolute_error": 0.375,
    "exact_match_rate": 0.75,
    "tolerance": 1,
    "within_tolerance_rate": 0.875
  },
  "error_type": {
    "labels": ["..."],
    "per_label": { "CORRECT": { "precision": 1.0, "recall": 1.0, "f1": 1.0, "support": 2 } },
    "macro_f1": 0.96,
    "confusion_matrix": { "CALCULATION_ERROR": { "OTHER": 1 } }
  },
  "review_decision_rate": { "accept": 0.625, "modify": 0.25, "reject": 0.125 },
  "teacher_modification_rate": 0.375,
  "provenance": {
    "model_names": ["example-model"],
    "prompt_versions": ["v1"],
    "missing_model_name_count": 0,
    "missing_prompt_version_count": 0
  },
  "timing": {
    "teacher_mean_seconds": 54.0,
    "teacher_median_seconds": 53.5,
    "ai_mean_seconds": 9.875,
    "ai_median_seconds": 9.5,
    "estimated_saving_ratio": 0.81713
  },
  "tokens": { "input_total": 6550, "output_total": 1205 },
  "unknown_labels": []
}
```

## 口径说明

**评分误差按原始分计算，不做归一化。** 归一化会把满分 5 分的题和满分 10 分的题
混在一起平均，反而失真。论文里比较不同题目时要按满分分组说明，不要用总平均掩盖差异。

**宏平均 F1 只在教师打过标签的类别上取平均。** 教师从未使用的标签没有召回可言，
计入平均会把指标无端拉低，那样的数字不反映真实分类能力。这些类别的
precision/recall/F1 仍然完整保留在 `per_label` 里，需要时可以自行重算。

**`estimated_saving_ratio` 是估算值**，由平均用时分母算出，不是逐样本配对比较。
教师用时为 0 或模型更慢时按 0 报，不报负数。论文中引用时应说明这是均值口径。
这个比例**仍然按均值算**，没有改成中位数口径——换了会让同一个数字在论文前后不一致。

**教师用时是前端计时，服务端只做范围校验**：从教师选中这条待复核项开始，到提交复核为止，
被挂起或离开页面的时间会计入。超过 1 小时（3600 秒）、负数或非数字一律按**缺失**记录并留一条服务端警告，
不阻断复核——教师不该为了一个统计字段被卡住。这类样本会在均值里消失，**报告里要交代剔除了几条**。

**用时同时报均值与中位数**，就是为了让"平均被少数样本拖高"这件事看得见：
教师中途去忙别的事，会留下一个几十分钟的离群值，均值随之抬升而中位数几乎不动。
两者接近说明分布不偏；中位数明显低于均值，说明均值不可单独引用。
评分误差这类有界量只有均值，离群值不会出现那里。
用时列整列为空时两个口径都报 `0`，与评分指标的空样本口径一致——
用时缺失本身应当在样本量里交代，而不是读成"0 秒批完"。

**复核决策分三项统计，`teacher_modification_rate` 是旧口径。**
`review_decision_rate` 的采纳 / 修改 / 驳回三项之和恒为 1；`teacher_modification_rate`
等于其中的 `modify + reject`，它保留下来只为让论文前几节已引用的数字仍然可比，
不是第四种决策。两处对不上说明导出环节改坏了其中一列，会由读取时的一致性校验挡住。

**数字保留 6 位小数**，用来消除浮点尾数，保证同一份输入永远得到同一份 JSON。
指标函数内部不四舍五入——`2/3` 就是 `0.666666...`，只在写文件时格式化。

**样本量小的时候不要下结论。** 示例数据只有 8 条，是用来说明口径的，
每一条都能在纸上复核。真实评测的样本量与抽样方式必须写进实验记录。

## 归档

`evaluation/output/` 已在 `.gitignore` 中，只是临时目录，结果不提交。
正式实验统一归档到**仓库之外**：

```text
E:\homework-analysis-experiment-archive\2026-09-main-study\
  protocol\   论文正式实验方案.md
  input\      grading-cases.csv, error-labels.csv
  output\     metrics.json
  logs\       run.txt
  checksums\  sha256.txt
```

放仓库外是有意的：仓库可能被克隆、被 fork、被公开，提交进去就收不回来。
**真实匿名样本、真实结果 JSON、身份映射表一律不进 Git**，
仓库里只保留脚本、契约文档和构造的示例数据。

归档目录整体拷贝即可交给导师或答辩委员会——用副本而不是符号链接，
链接换一台机器就断了。日志里不得出现姓名、学号、班级名、学校名、令牌或密钥。
