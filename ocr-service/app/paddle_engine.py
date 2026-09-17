"""PaddleOCR 适配器。

**本文件未经真实模型验证**：开发环境只装了 FastAPI/Pydantic 这些纯 Python 依赖，
Paddle 运行时在部署镜像里通过 extras 安装。因此这里刻意写得保守——只做
"把引擎输出翻译成契约结构"这一件事，认不出的形状一律跳过，绝不猜一个框出来。
首次在装了 Paddle 的机器上跑通后，应把真实样本的回归结果补进实验文档。

PaddleOCR 的 ``ocr()`` 给的是**文本检测框**，不是语义版面。所以第一版把每个文本框当作一个区域，
再按内容猜类型（含公式特征字符的记为 ``FORMULA``，其余记为 ``TEXT_BLOCK``）。
真正区分题干、选项与题图需要版面模型，那是后续模型升级的事，此处不假装已经做到。
"""

from __future__ import annotations

import os
import re
from typing import Any, Sequence

import numpy as np

from .pipeline import DetectedRegion, EngineUnavailableError, RawRecognition

#: 公式特征：LaTeX 常见控制符与数学符号。仅用于给出候选类型，不参与识别。
_FORMULA_HINT = re.compile(r"[\\^_{}]|√|π|≤|≥|≠|∥|∠|°")

DEFAULT_MODEL_VERSION = "3.7.0"


class PaddleOcrEngine:
    """延迟初始化的 PaddleOCR 引擎。

    模型在第一次真正识别时才加载：加载权重需要几秒到几十秒，放在启动期会让
    容器就绪探针超时而重启，而那时它其实完全健康。
    """

    name = "paddleocr"

    def __init__(self, model_version: str | None = None) -> None:
        self.model_version = model_version or os.environ.get("OCR_MODEL_VERSION", DEFAULT_MODEL_VERSION)
        self._engine: Any = None

    def _instance(self) -> Any:
        if self._engine is None:
            try:
                from paddleocr import PaddleOCR
            except ImportError as error:
                raise EngineUnavailableError(
                    "未安装 paddleocr，请安装 ocr-service 的 cpu 或 gpu extra") from error
            try:
                # use_angle_cls：手机拍照常带旋转，不做方向分类会把整页文字识别成乱码。
                self._engine = PaddleOCR(use_angle_cls=True, lang="ch")
            except Exception as error:
                raise EngineUnavailableError("PaddleOCR 初始化失败") from error
        return self._engine

    def detect(self, image: np.ndarray) -> Sequence[DetectedRegion]:
        return _to_detected_regions(self._instance().ocr(image, cls=True))

    def recognize(self, image: np.ndarray, region: DetectedRegion) -> RawRecognition:
        """兜底实现。

        文本检测已经带出了文字与置信度，正常情况下 :meth:`detect` 会把它们放进
        ``DetectedRegion.recognition``，本方法不会被调用。真被调用说明上游换了返回形状，
        此时返回空识别结果让区域仍然可见，而不是抛异常把整份文档判失败。
        """
        return RawRecognition(text=None, latex=None, confidence=0.0)


def _to_detected_regions(result: Any) -> list[DetectedRegion]:
    """把 PaddleOCR 的嵌套输出翻译成区域列表。

    输出形状随版本变动（``[[box, (text, score)], ...]`` 或按页分组的列表）。
    只认"四点多边形 + (文字, 置信度)"这一种能被明确解释的形状，其余跳过：
    少给教师一行候选只是少了条提示，把框画到别处却会让教师对着错位的题图做校对。
    """
    regions: list[DetectedRegion] = []
    for item in _unwrap(result):
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            continue
        points = _as_points(item[0])
        text, confidence = _as_text(item[1])
        if points is None or text is None:
            continue
        region = DetectedRegion(
            left=min(point[0] for point in points),
            top=min(point[1] for point in points),
            right=max(point[0] for point in points),
            bottom=max(point[1] for point in points),
            kind="FORMULA" if _FORMULA_HINT.search(text) else "TEXT_BLOCK",
            recognition=RawRecognition(
                text=text,
                latex=text if _FORMULA_HINT.search(text) else None,
                confidence=confidence,
            ),
        )
        regions.append(region)
    return regions


def _unwrap(result: Any) -> list[Any]:
    """展平到"一行一个条目"的层级，兼容按页嵌套与整体平铺两种返回。"""
    if not isinstance(result, list) or not result:
        return []
    first = result[0]
    if _looks_like_page(first):
        return [item for page in result for item in page]
    return list(result)


def _looks_like_page(candidate: Any) -> bool:
    """判断一层列表是不是"页"：页内的元素本身又是 ``[box, payload]`` 结构。"""
    if not isinstance(candidate, list) or not candidate:
        return False
    inner = candidate[0]
    return (isinstance(inner, (list, tuple)) and len(inner) == 2
            and isinstance(inner[0], (list, tuple)))


def _as_points(box: Any) -> list[tuple[float, float]] | None:
    try:
        return [(float(point[0]), float(point[1])) for point in box]
    except (TypeError, ValueError, IndexError):
        return None


def _as_text(payload: Any) -> tuple[str | None, float]:
    if isinstance(payload, (list, tuple)) and len(payload) >= 2:
        text, score = payload[0], payload[1]
        if isinstance(text, str) and text.strip():
            try:
                return text, float(score)
            except (TypeError, ValueError):
                return text, 0.0
    return None, 0.0
