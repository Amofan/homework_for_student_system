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
case_id,total_score,teacher_score,ai_score,teacher_error_type,ai_error_type,teacher_modified,teacher_seconds,ai_seconds,input_tokens,output_tokens
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

可选列留空表示**缺失**，不是 0——空单元格不会被算进平均值，也不会把令牌总量拉低。
整列都空时，输出里对应字段为 `null`，而不是 `0`，避免被读成"没有消耗"。

### 错因标签表 `error_labels.example.csv`

```text
error_type,label_zh
```

固定参与统计的标签全集。不传 `--labels` 时改用样本里实际出现过的标签。
两者取并集：表里有但样本没出现的类别会以零样本出现在结果里，
样本里出现但不在表里的标签会进 `unknown_labels` 并在命令行给出警告，不会被静默丢掉。

标签编码与后端 `AiGradingTaskWorker` 的 `ERROR_TYPES` 一致，另有规则评分产生的
`ANSWER_MISMATCH`。**改动后端标签时必须同步这张表**，否则分类指标会悄悄算错。

### 脱敏要求

输入必须已经脱敏：只有匿名编号，没有姓名、学号、班级、学校。
本脚本不做脱敏，也不应被当作脱敏工具——它假定上游已经处理干净，
这样脚本自身就不接触任何学生身份信息。

## 运行

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

跑测试：

```powershell
python -m unittest discover -s evaluation/tests -v
```

脚本只用 Python 标准库，评测可在离线环境重跑，不需要联网也不需要安装依赖。

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
  "teacher_modification_rate": 0.375,
  "timing": {
    "teacher_mean_seconds": 54.0,
    "ai_mean_seconds": 9.875,
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

**数字保留 6 位小数**，用来消除浮点尾数，保证同一份输入永远得到同一份 JSON。
指标函数内部不四舍五入——`2/3` 就是 `0.666666...`，只在写文件时格式化。

**样本量小的时候不要下结论。** 示例数据只有 8 条，是用来说明口径的，
每一条都能在纸上复核。真实评测的样本量与抽样方式必须写进实验记录。

## 归档

`evaluation/output/` 已在 `.gitignore` 中，结果不提交。
论文引用时把最终匿名数据与结果 JSON 一起另行归档到受控位置，
不要把真实学生信息或以真实样本算出的明细提交进仓库。
