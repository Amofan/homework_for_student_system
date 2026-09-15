#!/usr/bin/env python3
"""论文用离线评测：读匿名评分样本，算出评分误差与错因分类指标，输出 JSON。

设计前提
--------
输入必须是**已经脱敏**的样本：只有匿名样本编号，没有姓名、学号、班级或学校。
本脚本不做脱敏，也不应被当作脱敏工具使用——它假定上游已经处理干净，
这样脚本本身就不接触任何学生身份信息。

只用 Python 标准库。评测要能在答辩现场的离线环境里重跑，
引入第三方依赖会让"结果可复现"变成"先装对环境再说"。

指标口径
--------
- 评分误差按**原始分**计算，不做归一化：不同题目总分不同时，
  归一化会把满分 5 分的题和满分 10 分的题混在一起平均，反而失真。
- 容差命中率默认取容差 1 分（`--tolerance` 可改）。
- 宏平均 F1 只在教师打过标签的类别上取平均。教师从未使用的标签
  参与平均会把指标无端拉低，这样的数字不能反映真实分类能力；
  但未打标签的类别仍然完整出现在输出里，不会被藏起来。
- 教师复核分三种决策：采纳、修改、驳回。它们各自成比例，
  `teacher_modification_rate` 是旧口径（修改 + 驳回），仅为与论文已有数字可比而保留。
- 每行还带模型名与提示词版本，用于交代实验的模型溯源。
- 所有比率保留 6 位小数，消除浮点尾数，保证同一份输入永远得到同一份 JSON。
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, NamedTuple, Sequence

ROUNDING = 6

REQUIRED_COLUMNS = (
    "case_id",
    "total_score",
    "teacher_score",
    "ai_score",
    "teacher_error_type",
    "ai_error_type",
    "teacher_modified",
    "review_decision",
)

OPTIONAL_COLUMNS = ("teacher_seconds", "ai_seconds", "input_tokens", "output_tokens",
                    "model_name", "prompt_version")

# 教师的三种复核决策。采纳与驳回是不同的教学判断，不能折叠成一个"改过"布尔值。
REVIEW_DECISIONS = frozenset({"ACCEPT", "MODIFY", "REJECT"})

TRUE_VALUES = frozenset({"true", "1", "yes", "y", "是"})
FALSE_VALUES = frozenset({"false", "0", "no", "n", "否", ""})


@dataclass(frozen=True)
class GradingCase:
    """一条匿名评分样本。可选列缺失时为 None，而不是 0。"""

    case_id: str
    total_score: float
    teacher_score: float
    ai_score: float
    teacher_error_type: str
    ai_error_type: str
    teacher_modified: bool
    teacher_seconds: float | None
    ai_seconds: float | None
    input_tokens: int | None
    output_tokens: int | None
    review_decision: str
    model_name: str | None = None
    prompt_version: str | None = None


class LabelMetrics(NamedTuple):
    """单个错因标签的查准、查全与 F1。"""

    precision: float
    recall: float
    f1: float
    support: int


# --------------------------------------------------------------------------
# 基础统计
# --------------------------------------------------------------------------

def mean(values: Iterable[float | None]) -> float:
    """缺失值不参与平均；全缺或空集返回 0，避免除零。"""
    present = [value for value in values if value is not None]
    if not present:
        return 0.0
    return sum(present) / len(present)


def median(values: Iterable[float | None]) -> float:
    """中位数。与 mean 同样跳过缺失值，全缺或空集返回 0，保持两个口径可直接对照。

    加它的原因是用时分布不对称：教师中途去处理别的事，会留下一个几十分钟的
    离群值，把均值整体拉高。中位数不受个别离群值影响，两者一起看才知道
    平均是被少数样本拖上去的，还是本来就慢。评分误差这类有界量仍用 mean。
    """
    present = [value for value in values if value is not None]
    if not present:
        return 0.0
    return float(statistics.median(present))


def mean_absolute_error(pairs: Sequence[tuple[float, float]]) -> float:
    """平均绝对误差。pairs 是 (教师分, 模型分)。"""
    return mean([abs(teacher - ai) for teacher, ai in pairs])


def exact_match_rate(pairs: Sequence[tuple[float, float]]) -> float:
    """评分完全一致率。"""
    return _rate(sum(1 for teacher, ai in pairs if teacher == ai), len(pairs))


def within_tolerance_rate(pairs: Sequence[tuple[float, float]], tolerance: float) -> float:
    """误差不超过 tolerance 分的命中率。容差为 0 时退化为完全一致率。"""
    return _rate(sum(1 for teacher, ai in pairs if abs(teacher - ai) <= tolerance), len(pairs))


def estimated_saving_ratio(teacher_mean_seconds: float, ai_mean_seconds: float) -> float:
    """估算节省比例。教师用时为 0 或模型更慢时返回 0，不返回负数。"""
    if teacher_mean_seconds <= 0:
        return 0.0
    saving = (teacher_mean_seconds - ai_mean_seconds) / teacher_mean_seconds
    return max(0.0, saving)


def _rate(hits: int, total: int) -> float:
    """返回精确比率。舍入只在写 JSON 时做，否则 2/3 会变成 0.666667，
    调用方按分数形式断言就会失配。"""
    return hits / total if total else 0.0


def _round(value: float) -> float:
    return round(value, ROUNDING)


# --------------------------------------------------------------------------
# 错因分类
# --------------------------------------------------------------------------

def label_universe(cases: Sequence[GradingCase], labels: Sequence[str] | None = None) -> list[str]:
    """参与统计的标签全集：给定标签表与实际出现过的标签取并集。

    取并集是为了既不漏报"表里有但样本里没出现"的类别，
    也不静默丢掉"样本里出现但不在表里"的意外标签。
    """
    seen = {case.teacher_error_type for case in cases} | {case.ai_error_type for case in cases}
    return sorted(set(labels or ()) | seen)


def confusion_matrix(cases: Sequence[GradingCase],
                     labels: Sequence[str] | None = None) -> dict[str, dict[str, int]]:
    """教师标签 × 模型标签的计数矩阵，含零值单元格。"""
    universe = label_universe(cases, labels)
    matrix = {teacher: {ai: 0 for ai in universe} for teacher in universe}
    for case in cases:
        matrix[case.teacher_error_type][case.ai_error_type] += 1
    return matrix


def per_label_metrics(cases: Sequence[GradingCase],
                      labels: Sequence[str]) -> dict[str, LabelMetrics]:
    """逐标签的查准率、查全率与 F1。"""
    matrix = confusion_matrix(cases, labels)
    metrics: dict[str, LabelMetrics] = {}
    for label in label_universe(cases, labels):
        true_positive = matrix[label][label]
        predicted = sum(matrix[teacher][label] for teacher in matrix)
        actual = sum(matrix[label].values())
        precision = true_positive / predicted if predicted else 0.0
        recall = true_positive / actual if actual else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        metrics[label] = LabelMetrics(precision, recall, f1, actual)
    return metrics


def macro_f1(metrics: dict[str, LabelMetrics]) -> float:
    """宏平均 F1，只在教师打过标签的类别上平均。

    教师从未使用的标签没有召回可言，计入平均会让指标失真。
    这些类别的明细仍然保留在输出里。
    """
    scored = [metric.f1 for metric in metrics.values() if metric.support > 0]
    return sum(scored) / len(scored) if scored else 0.0


# --------------------------------------------------------------------------
# 读取
# --------------------------------------------------------------------------

def read_grading_cases(path: Path) -> list[GradingCase]:
    """读取匿名评分样本。列缺失时报出缺的是哪一列，而不是抛 KeyError。"""
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        header = reader.fieldnames or []
        missing = [column for column in REQUIRED_COLUMNS if column not in header]
        if missing:
            raise ValueError(f"{path} 缺少必需列：{', '.join(missing)}")
        return [_to_case(row, path) for row in reader]


def read_error_labels(path: Path) -> list[str]:
    """读取标签表，返回标签编码列表（中文列仅供人阅读）。"""
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if "error_type" not in (reader.fieldnames or []):
            raise ValueError(f"{path} 缺少必需列：error_type")
        return [row["error_type"].strip() for row in reader if row["error_type"].strip()]


def _to_case(row: dict[str, str], path: Path) -> GradingCase:
    def number(column: str) -> float | None:
        raw = (row.get(column) or "").strip()
        if not raw:
            return None
        try:
            return float(raw)
        except ValueError as error:
            raise ValueError(f"{path} 的 {column} 不是数字：{raw!r}") from error

    def count(column: str) -> int | None:
        """令牌数是计数，保持整型——写成 6550.0 会让人怀疑统计口径。"""
        value = number(column)
        if value is None:
            return None
        if value != int(value):
            raise ValueError(f"{path} 的 {column} 必须是整数：{value!r}")
        return int(value)

    def required(column: str) -> str:
        raw = (row.get(column) or "").strip()
        if not raw:
            raise ValueError(f"{path} 的 {column} 不能为空")
        return raw

    def optional_text(column: str) -> str | None:
        """溯源列缺失记 None，与空字符串区分开，好让缺失计数有意义。"""
        raw = (row.get(column) or "").strip()
        return raw or None

    modified = (row.get("teacher_modified") or "").strip().lower()
    if modified not in TRUE_VALUES and modified not in FALSE_VALUES:
        raise ValueError(f"{path} 的 teacher_modified 只能是 true/false：{modified!r}")

    decision = required("review_decision").upper()
    if decision not in REVIEW_DECISIONS:
        raise ValueError(
            f"{path} 的 review_decision 只能是 ACCEPT/MODIFY/REJECT：{decision!r}")
    # 两个字段表达同一件事，一旦不一致就说明导出环节改坏了其中一列。
    # 静默取其一会让论文里"修改率"与"决策比例"互相矛盾，且矛盾是看不出来的。
    if modified in TRUE_VALUES and decision == "ACCEPT":
        raise ValueError(f"{path} 的 teacher_modified 与 review_decision 不一致")
    if modified in FALSE_VALUES and decision != "ACCEPT":
        raise ValueError(f"{path} 的 teacher_modified 与 review_decision 不一致")

    return GradingCase(
        case_id=required("case_id"),
        total_score=number("total_score") or 0.0,
        teacher_score=number("teacher_score") or 0.0,
        ai_score=number("ai_score") or 0.0,
        teacher_error_type=required("teacher_error_type"),
        ai_error_type=required("ai_error_type"),
        teacher_modified=modified in TRUE_VALUES,
        teacher_seconds=number("teacher_seconds"),
        ai_seconds=number("ai_seconds"),
        input_tokens=count("input_tokens"),
        output_tokens=count("output_tokens"),
        review_decision=decision,
        model_name=optional_text("model_name"),
        prompt_version=optional_text("prompt_version"),
    )


# --------------------------------------------------------------------------
# 汇总
# --------------------------------------------------------------------------

def evaluate(cases: Sequence[GradingCase], labels: Sequence[str] | None = None,
             tolerance: float = 1) -> dict:
    """算出全部论文指标。同一份输入永远得到同一份结果。"""
    pairs = [(case.teacher_score, case.ai_score) for case in cases]
    metrics = per_label_metrics(cases, list(labels or ()))
    teacher_mean = mean([case.teacher_seconds for case in cases])
    teacher_median = median([case.teacher_seconds for case in cases])
    ai_mean = mean([case.ai_seconds for case in cases])
    ai_median = median([case.ai_seconds for case in cases])
    decisions = [case.review_decision for case in cases]

    return {
        "sample_count": len(cases),
        "score": {
            "mean_absolute_error": _round(mean_absolute_error(pairs)),
            "exact_match_rate": _round(exact_match_rate(pairs)),
            "tolerance": tolerance,
            "within_tolerance_rate": _round(within_tolerance_rate(pairs, tolerance)),
        },
        "error_type": {
            "labels": label_universe(cases, labels),
            "per_label": {
                label: {"precision": _round(metric.precision), "recall": _round(metric.recall),
                        "f1": _round(metric.f1), "support": metric.support}
                for label, metric in metrics.items()
            },
            "macro_f1": _round(macro_f1(metrics)),
            "confusion_matrix": confusion_matrix(cases, labels),
        },
        # 采纳 / 修改 / 驳回各自的比例。三项之和为 1，好让读者一眼看出教师
        # 到底是"基本认可模型"还是"大量推翻"，而不是只有一个笼统的修改率。
        "review_decision_rate": {
            "accept": _round(_rate(decisions.count("ACCEPT"), len(cases))),
            "modify": _round(_rate(decisions.count("MODIFY"), len(cases))),
            "reject": _round(_rate(decisions.count("REJECT"), len(cases))),
        },
        # 保留旧口径：论文前几节的数字按它统计，删掉会让新旧结果失去可比性。
        # 它与 review_decision_rate 的 modify + reject 必然相等，这个恒等式由
        # read_grading_cases 的一致性校验保证，而不是靠调用方自觉。
        "teacher_modification_rate": _round(
            _rate(sum(1 for case in cases if case.teacher_modified), len(cases))),
        # 模型名与提示词版本决定了"这个结果是用哪个模型、哪版提示词跑出来的"，
        # 不记下来，半年后没人能说清指标对应的是哪一次实验。
        "provenance": {
            "model_names": sorted({case.model_name for case in cases if case.model_name}),
            "prompt_versions": sorted(
                {case.prompt_version for case in cases if case.prompt_version}),
            "missing_model_name_count": sum(case.model_name is None for case in cases),
            "missing_prompt_version_count": sum(case.prompt_version is None for case in cases),
        },
        "timing": {
            "teacher_mean_seconds": _round(teacher_mean),
            "teacher_median_seconds": _round(teacher_median),
            "ai_mean_seconds": _round(ai_mean),
            "ai_median_seconds": _round(ai_median),
            # 省时比例仍按均值算：论文里引用的是"平均节省多少时间"，
            # 换成中位数口径会让这个已有数字悄悄改变。两个口径都在上面，
            # 中位数只用来判断均值有没有被离群值带偏。
            "estimated_saving_ratio": _round(estimated_saving_ratio(teacher_mean, ai_mean)),
        },
        "tokens": {
            "input_total": _total(cases, "input_tokens"),
            "output_total": _total(cases, "output_tokens"),
        },
        # 只有给了标签表才谈得上"表外标签"。不传标签表时标签全集本来就取自样本，
        # 再报"未知标签"等于把每个标签都报一遍，是纯粹的噪声。
        "unknown_labels": sorted(
            ({case.teacher_error_type for case in cases} | {case.ai_error_type for case in cases})
            - set(labels)) if labels else [],
    }


def _total(cases: Sequence[GradingCase], field: str) -> int | None:
    """令牌总量。整列都缺时返回 None —— 报 0 会被误读成"没有消耗"。"""
    values = [getattr(case, field) for case in cases]
    if not any(value is not None for value in values):
        return None
    return sum(value for value in values if value is not None)


# --------------------------------------------------------------------------
# 命令行
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="按匿名样本计算评分误差、容差命中率与错因分类指标。")
    parser.add_argument("--grading", required=True, type=Path, help="匿名评分样本 CSV")
    parser.add_argument("--labels", type=Path, help="错因标签表 CSV，用于固定标签全集")
    parser.add_argument("--output", required=True, type=Path, help="指标结果 JSON 输出路径")
    parser.add_argument("--tolerance", type=float, default=1, help="容差分数，默认 1")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        cases = read_grading_cases(args.grading)
        labels = read_error_labels(args.labels) if args.labels else None
    except (OSError, ValueError) as error:
        print(f"读取失败：{error}", file=sys.stderr)
        return 2

    result = evaluate(cases, labels, tolerance=args.tolerance)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                           encoding="utf-8")

    print(f"样本数 {result['sample_count']}，"
          f"平均绝对误差 {result['score']['mean_absolute_error']}，"
          f"容差 {result['score']['tolerance']} 分命中率 {result['score']['within_tolerance_rate']}，"
          f"错因宏平均 F1 {result['error_type']['macro_f1']}")
    decision = result["review_decision_rate"]
    print(f"复核决策：采纳 {decision['accept']}，修改 {decision['modify']}，驳回 {decision['reject']}；"
          f"修改率（旧口径）{result['teacher_modification_rate']}")
    provenance = result["provenance"]
    print(f"模型溯源：模型 {provenance['model_names']}，"
          f"提示词版本 {provenance['prompt_versions']}，"
          f"模型名缺失 {provenance['missing_model_name_count']} 条，"
          f"提示词版本缺失 {provenance['missing_prompt_version_count']} 条")
    if result["unknown_labels"]:
        print(f"警告：样本中出现标签表以外的标签 {result['unknown_labels']}", file=sys.stderr)
    print(f"结果已写入 {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
