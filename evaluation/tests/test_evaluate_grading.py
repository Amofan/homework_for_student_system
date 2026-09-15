"""离线评测指标的确定性单元测试。

只用手写的小样本断言，样本量小到可以在纸上复核——指标口径一旦被改动，
这些数字会立刻失配，比"跑一下看看有没有报错"可靠得多。
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import evaluate_grading as eg  # noqa: E402  （必须先补 sys.path 才能导入同目录脚本）

HEADER = ("case_id,total_score,teacher_score,ai_score,teacher_error_type,"
          "ai_error_type,teacher_modified,teacher_seconds,ai_seconds,"
          "input_tokens,output_tokens,review_decision,model_name,prompt_version")


def write_csv(directory: Path, name: str, rows: list) -> Path:
    path = directory / name
    path.write_text("\n".join([HEADER] + rows) + "\n", encoding="utf-8")
    return path


class ScoreMetricsTest(unittest.TestCase):
    """评分误差口径。"""

    def test_score_metrics(self):
        pairs = [(8, 8), (6, 7), (5, 3)]

        self.assertEqual(eg.mean_absolute_error(pairs), 1.0)
        self.assertEqual(eg.within_tolerance_rate(pairs, tolerance=1), 2 / 3)

    def test_exact_match_counts_only_identical_scores(self):
        pairs = [(8, 8), (6, 7), (5, 3), (4, 4)]

        self.assertEqual(eg.exact_match_rate(pairs), 2 / 4)

    def test_zero_samples_returns_zero_instead_of_dividing_by_zero(self):
        # 空样本是真实场景（还没攒够数据就想先跑一次），必须给出 0 而不是抛异常
        self.assertEqual(eg.mean_absolute_error([]), 0.0)
        self.assertEqual(eg.exact_match_rate([]), 0.0)
        self.assertEqual(eg.within_tolerance_rate([], tolerance=1), 0.0)
        self.assertEqual(eg.mean([]), 0.0)
        self.assertEqual(eg.estimated_saving_ratio(0, 0), 0.0)

    def test_tolerance_zero_degrades_to_exact_match(self):
        pairs = [(8, 8), (6, 7)]

        self.assertEqual(eg.within_tolerance_rate(pairs, tolerance=0), 0.5)

    def test_mean_ignores_missing_values(self):
        # 用时和令牌是可选列，缺一列不该把平均值拉成 0
        self.assertEqual(eg.mean([10.0, None, 20.0]), 15.0)
        self.assertEqual(eg.mean([None, None]), 0.0)

    def test_median_skips_missing_values_like_mean(self):
        self.assertEqual(eg.median([10.0, None, 20.0]), 15.0)
        self.assertEqual(eg.median([None, None]), 0.0)

    def test_median_averages_the_two_middle_values(self):
        # 偶数个样本取中间两个的平均，这是中位数的定义，不是取其中一个
        self.assertEqual(eg.median([1.0, 2.0, 3.0, 4.0]), 2.5)
        self.assertEqual(eg.median([3.0, 1.0, 2.0]), 2.0)

    def test_median_resists_the_outlier_that_drags_the_mean(self):
        # 教师中途去忙别的事，留下一小时的空档：均值被带偏，中位数基本不动。
        # 两个口径并列输出，就是为了让这种偏斜看得见
        seconds = [30.0, 32.0, 35.0, 40.0, 3600.0]

        self.assertGreater(eg.mean(seconds), 700)
        self.assertEqual(eg.median(seconds), 35.0)

    def test_saving_ratio_never_goes_negative(self):
        # AI 比教师还慢时，节省比例按 0 报，不报负数
        self.assertEqual(eg.estimated_saving_ratio(10.0, 25.0), 0.0)
        self.assertEqual(eg.estimated_saving_ratio(100.0, 20.0), 0.8)


class ConfusionMatrixTest(unittest.TestCase):
    """错因分类口径。"""

    def test_confusion_matrix_counts_label_pairs(self):
        cases = [
            eg.GradingCase("C-1", 10, 8, 8, "CALCULATION_ERROR", "CALCULATION_ERROR", False, None, None, None, None, "ACCEPT"),
            eg.GradingCase("C-2", 10, 5, 3, "METHOD_ERROR", "CALCULATION_ERROR", True, None, None, None, None, "MODIFY"),
            eg.GradingCase("C-3", 10, 8, 8, "CALCULATION_ERROR", "CALCULATION_ERROR", False, None, None, None, None, "ACCEPT"),
        ]

        matrix = eg.confusion_matrix(cases)

        self.assertEqual(matrix["CALCULATION_ERROR"]["CALCULATION_ERROR"], 2)
        self.assertEqual(matrix["METHOD_ERROR"]["CALCULATION_ERROR"], 1)
        self.assertEqual(matrix["METHOD_ERROR"]["METHOD_ERROR"], 0)

    def test_per_label_metrics_on_a_known_matrix(self):
        cases = [
            eg.GradingCase("C-1", 10, 0, 0, "CORRECT", "CORRECT", False, None, None, None, None, "ACCEPT"),
            eg.GradingCase("C-2", 10, 2, 2, "CALCULATION_ERROR", "CALCULATION_ERROR", False, None, None, None, None, "ACCEPT"),
            eg.GradingCase("C-3", 10, 2, 2, "CALCULATION_ERROR", "CORRECT", False, None, None, None, None, "ACCEPT"),
        ]

        metrics = eg.per_label_metrics(cases, ["CORRECT", "CALCULATION_ERROR"])

        # CORRECT：预测 2 次命中 1 次（C-3 误报），实际 1 次全部召回
        correct = metrics["CORRECT"]
        self.assertEqual(correct.precision, 0.5)
        self.assertEqual(correct.recall, 1.0)
        self.assertEqual(correct.support, 1)
        # CALCULATION_ERROR：预测 1 次命中，实际 2 次漏报 1 次
        calculation = metrics["CALCULATION_ERROR"]
        self.assertEqual(calculation.precision, 1.0)
        self.assertEqual(calculation.recall, 0.5)
        self.assertAlmostEqual(calculation.f1, 2 / 3)

    def test_label_predicted_but_never_true_scores_zero(self):
        cases = [
            eg.GradingCase("C-1", 10, 0, 0, "CORRECT", "OTHER", False, None, None, None, None, "ACCEPT"),
        ]

        metrics = eg.per_label_metrics(cases, ["CORRECT", "OTHER"])

        self.assertEqual(metrics["OTHER"].precision, 0.0)
        self.assertEqual(metrics["OTHER"].recall, 0.0)
        self.assertEqual(metrics["OTHER"].f1, 0.0)

    def test_macro_f1_averages_only_labels_present_in_teacher_labels(self):
        # 教师从未打过的标签（这里没有）不该被算进宏平均，否则指标会被拉低失真
        cases = [
            eg.GradingCase("C-1", 10, 0, 0, "CORRECT", "CORRECT", False, None, None, None, None, "ACCEPT"),
            eg.GradingCase("C-2", 10, 2, 2, "CALCULATION_ERROR", "CALCULATION_ERROR", False, None, None, None, None, "ACCEPT"),
        ]

        metrics = eg.per_label_metrics(cases, ["CORRECT", "CALCULATION_ERROR", "METHOD_ERROR"])

        self.assertEqual(eg.macro_f1(metrics), 1.0)

    def test_no_label_table_means_no_unknown_label_warning(self):
        # 不传 --labels 时标签全集本就取自样本，不该把每个标签都报成"未知"
        cases = [
            eg.GradingCase("C-1", 10, 0, 0, "CORRECT", "CORRECT", False, None, None, None, None, "ACCEPT"),
        ]

        self.assertEqual(eg.evaluate(cases)["unknown_labels"], [])

    def test_unknown_labels_are_reported_not_silently_dropped(self):
        cases = [
            eg.GradingCase("C-1", 10, 0, 0, "CORRECT", "CORRECT", False, None, None, None, None, "ACCEPT"),
            eg.GradingCase("C-2", 10, 5, 5, "NOT_A_REAL_LABEL", "ALSO_FAKE", False, None, None, None, None, "ACCEPT"),
        ]

        result = eg.evaluate(cases, ["CORRECT"])

        self.assertEqual(result["unknown_labels"], ["ALSO_FAKE", "NOT_A_REAL_LABEL"])
        # 未知标签照样进混淆矩阵，不能被丢掉
        self.assertEqual(result["error_type"]["confusion_matrix"]["NOT_A_REAL_LABEL"]["ALSO_FAKE"], 1)


class ReviewDecisionTest(unittest.TestCase):
    """教师复核决策与模型溯源口径。"""

    def test_review_decision_rates_keep_accept_modify_and_reject_separate(self):
        cases = [
            eg.GradingCase("C-1", 10, 8, 8, "CORRECT", "CORRECT", False,
                           40, 8, 500, 100, "ACCEPT", "model-a", "v1"),
            eg.GradingCase("C-2", 10, 6, 8, "METHOD_ERROR", "CALCULATION_ERROR", True,
                           60, 9, 520, 110, "MODIFY", "model-a", "v1"),
            eg.GradingCase("C-3", 10, 5, 8, "CONCEPT_ERROR", "METHOD_ERROR", True,
                           70, 10, 530, 120, "REJECT", "model-a", "v1"),
            eg.GradingCase("C-4", 10, 10, 10, "CORRECT", "CORRECT", False,
                           30, 7, 490, 90, "ACCEPT", "model-a", "v1"),
        ]

        result = eg.evaluate(cases)

        self.assertEqual(result["review_decision_rate"], {
            "accept": 0.5, "modify": 0.25, "reject": 0.25,
        })
        # 旧口径必须仍是"修改 + 驳回"。论文前几节引用过这个数字，
        # 它若与三项比例对不上，两处结论就会互相矛盾
        self.assertEqual(result["teacher_modification_rate"],
                         result["review_decision_rate"]["modify"]
                         + result["review_decision_rate"]["reject"])

    def test_empty_cases_report_zero_rates_and_no_provenance(self):
        result = eg.evaluate([])

        self.assertEqual(result["review_decision_rate"],
                         {"accept": 0.0, "modify": 0.0, "reject": 0.0})
        self.assertEqual(result["provenance"]["model_names"], [])
        self.assertEqual(result["provenance"]["missing_model_name_count"], 0)

    def test_provenance_sorts_model_names_and_prompt_versions(self):
        cases = [
            eg.GradingCase("C-1", 10, 8, 8, "CORRECT", "CORRECT", False,
                           None, None, None, None, "ACCEPT", "model-b", "v2"),
            eg.GradingCase("C-2", 10, 8, 8, "CORRECT", "CORRECT", False,
                           None, None, None, None, "ACCEPT", "model-a", "v1"),
            eg.GradingCase("C-3", 10, 8, 8, "CORRECT", "CORRECT", False,
                           None, None, None, None, "ACCEPT", "model-a", "v2"),
        ]

        provenance = eg.evaluate(cases)["provenance"]

        # 集合去重后排序，顺序稳定，同一份输入永远得到同一份 JSON
        self.assertEqual(provenance["model_names"], ["model-a", "model-b"])
        self.assertEqual(provenance["prompt_versions"], ["v1", "v2"])
        self.assertEqual(provenance["missing_model_name_count"], 0)
        self.assertEqual(provenance["missing_prompt_version_count"], 0)

    def test_missing_provenance_is_counted_rather_than_assumed(self):
        # prompt_version 在建表时有默认值，正常不会缺；模型名则可能因任务行
        # 不存在而为空。缺多少条要能看见，而不是被默认成一个具体的模型。
        cases = [
            eg.GradingCase("C-1", 10, 8, 8, "CORRECT", "CORRECT", False,
                           None, None, None, None, "ACCEPT", "model-a", "v1"),
            eg.GradingCase("C-2", 10, 8, 8, "CORRECT", "CORRECT", False,
                           None, None, None, None, "ACCEPT", None, None),
        ]

        provenance = eg.evaluate(cases)["provenance"]

        self.assertEqual(provenance["model_names"], ["model-a"])
        self.assertEqual(provenance["prompt_versions"], ["v1"])
        self.assertEqual(provenance["missing_model_name_count"], 1)
        self.assertEqual(provenance["missing_prompt_version_count"], 1)


class ReadGradingCasesTest(unittest.TestCase):
    """CSV 读取。"""

    def test_reads_rows_and_optional_columns(self):
        with tempfile.TemporaryDirectory() as raw:
            path = write_csv(Path(raw), "cases.csv", [
                "C-1,10,8,8,CALCULATION_ERROR,CALCULATION_ERROR,false,40,10,800,120,"
                "ACCEPT,model-a,v1",
                "C-2,10,5,3,METHOD_ERROR,CALCULATION_ERROR,true,60,12,,,MODIFY,,v2",
            ])

            cases = eg.read_grading_cases(path)

        self.assertEqual(len(cases), 2)
        self.assertFalse(cases[0].teacher_modified)
        self.assertEqual(cases[0].input_tokens, 800)
        self.assertEqual(cases[0].review_decision, "ACCEPT")
        self.assertEqual(cases[0].model_name, "model-a")
        self.assertTrue(cases[1].teacher_modified)
        # 空单元格是缺失值而不是 0，否则会污染平均值
        self.assertIsNone(cases[1].input_tokens)
        # 溯源列的空单元格同样是缺失，不能变成空字符串
        self.assertIsNone(cases[1].model_name)
        self.assertEqual(cases[1].prompt_version, "v2")

    def test_teacher_modified_must_match_review_decision(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "inconsistent.csv"
            path.write_text(
                "case_id,total_score,teacher_score,ai_score,teacher_error_type,"
                "ai_error_type,teacher_modified,teacher_seconds,ai_seconds,input_tokens,"
                "output_tokens,review_decision,model_name,prompt_version\n"
                "C-1,10,6,8,METHOD_ERROR,CALCULATION_ERROR,false,60,9,520,110,"
                "MODIFY,deepseek-v4-pro,v1\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                ValueError, "teacher_modified 与 review_decision 不一致"
            ):
                eg.read_grading_cases(path)

    def test_review_decision_outside_the_three_values_is_rejected(self):
        # 复核决策是枚举，出现第四种取值说明导出环节改了口径，必须报错而不是照收
        with tempfile.TemporaryDirectory() as raw:
            path = write_csv(Path(raw), "bad-decision.csv", [
                "C-1,10,6,8,METHOD_ERROR,CALCULATION_ERROR,true,60,9,520,110,"
                "MAYBE,,v1",
            ])

            with self.assertRaisesRegex(ValueError, "review_decision 只能是"):
                eg.read_grading_cases(path)

    def test_missing_required_column_is_a_clear_error(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "broken.csv"
            path.write_text("case_id,teacher_score\nC-1,8\n", encoding="utf-8")

            with self.assertRaises(ValueError) as raised:
                eg.read_grading_cases(path)

        self.assertIn("ai_score", str(raised.exception))


class EndToEndTest(unittest.TestCase):
    """整条链路：示例数据进，指标 JSON 出。数字全部可手算复核。"""

    def test_example_dataset_matches_hand_computed_metrics(self):
        data = Path(__file__).resolve().parents[1] / "data"
        cases = eg.read_grading_cases(data / "grading_cases.example.csv")
        labels = eg.read_error_labels(data / "error_labels.example.csv")

        result = eg.evaluate(cases, labels, tolerance=1)

        self.assertEqual(result["sample_count"], 8)
        # 误差依次是 0 0 1 2 0 0 0 0；CASE-003 差 1 分、CASE-004 差 2 分，其余六条完全一致
        self.assertEqual(result["score"]["mean_absolute_error"], 0.375)
        self.assertEqual(result["score"]["exact_match_rate"], 0.75)
        # 容差 1 分时只有 CASE-004 超出，因此是 7/8
        self.assertEqual(result["score"]["within_tolerance_rate"], 0.875)
        # 只有 CASE-003、CASE-004、CASE-008 被教师改动
        self.assertEqual(result["teacher_modification_rate"], 0.375)
        # 八条里五条采纳、两条修改、一条驳回；驳回没有被折叠进修改
        self.assertEqual(result["review_decision_rate"],
                         {"accept": 0.625, "modify": 0.25, "reject": 0.125})
        self.assertEqual(result["provenance"]["model_names"], ["example-model"])
        self.assertEqual(result["provenance"]["prompt_versions"], ["v1"])
        self.assertEqual(result["provenance"]["missing_model_name_count"], 0)
        self.assertEqual(result["provenance"]["missing_prompt_version_count"], 0)
        # 教师用时段与 AI 用时段各自求和后取均值
        self.assertEqual(result["timing"]["teacher_mean_seconds"], 54.0)
        self.assertEqual(result["timing"]["ai_mean_seconds"], 9.875)
        # 教师用时排序后中间两项是 52 与 55；AI 用时是 9 与 10。
        # 示例数据没有离群值，中位数与均值接近，说明这组样本本身不偏
        self.assertEqual(result["timing"]["teacher_median_seconds"], 53.5)
        self.assertEqual(result["timing"]["ai_median_seconds"], 9.5)
        self.assertAlmostEqual(result["timing"]["estimated_saving_ratio"], 0.81713, places=5)
        self.assertEqual(result["tokens"]["input_total"], 6550)
        self.assertEqual(result["tokens"]["output_total"], 1205)
        # 只有 CALCULATION_ERROR 有一次漏报（CASE-008 被判成 OTHER）
        self.assertAlmostEqual(result["error_type"]["per_label"]["CALCULATION_ERROR"]["f1"], 0.8)
        self.assertEqual(result["error_type"]["macro_f1"], 0.96)
        self.assertEqual(result["unknown_labels"], [])

    def test_output_json_is_reproducible(self):
        data = Path(__file__).resolve().parents[1] / "data"
        cases = eg.read_grading_cases(data / "grading_cases.example.csv")
        labels = eg.read_error_labels(data / "error_labels.example.csv")

        first = json.dumps(eg.evaluate(cases, labels), ensure_ascii=False, sort_keys=True)
        second = json.dumps(eg.evaluate(cases, labels), ensure_ascii=False, sort_keys=True)

        self.assertEqual(first, second)

    def test_empty_dataset_still_produces_a_usable_report(self):
        result = eg.evaluate([], eg.read_error_labels(
            Path(__file__).resolve().parents[1] / "data" / "error_labels.example.csv"))

        self.assertEqual(result["sample_count"], 0)
        self.assertEqual(result["score"]["mean_absolute_error"], 0.0)
        self.assertEqual(result["error_type"]["macro_f1"], 0.0)


if __name__ == "__main__":
    unittest.main()
