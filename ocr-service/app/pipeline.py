"""预处理、版面检测、识别与归一化。

四个阶段都是显式函数，而不是一个大方法：模型升级时通常只换其中一段，
把它们串在一起会让"换检测模型"变成"重写整个流程"。

识别引擎通过 :class:`OcrEngine` 协议注入。测试注入固定输出的假引擎，
因此跑测试既不需要下载权重也不需要联网——这是让契约测试能进 CI 的前提。
"""

from __future__ import annotations

import base64
import binascii
import io
from dataclasses import dataclass, replace
from typing import Protocol, Sequence

import numpy as np
from PIL import Image, UnidentifiedImageError

from .contracts import (
    SCHEMA_VERSION,
    AnalyzeRequest,
    AnalyzeResponse,
    Page,
    QuestionBoxInput,
    Region,
    TemplateInput,
    TemplatePageInput,
)

#: 处理副本的最长边。超过就等比缩小后再识别：版面检测与识别的耗时近似按像素数增长，
#: 一张 6000×8000 的手机照会让单页耗时从几秒涨到几十秒，而缩到 4000 像素几乎不影响识别率。
#: 归一化坐标与缩放无关，所以缩小不会让返回的框错位。
MAX_PROCESSING_EDGE = 4000

#: 归一化坐标保留的小数位数，与数据库 decimal(8,7) 对齐，避免存进去时被静默四舍五入。
NORMALIZED_DECIMALS = 7

#: 配准命中阈值：一张模板页上的题目框，被提交页区域压住的平均比例达到这个值，
#: 才认为"这一页就是这张模板页"。取 0.5 的含义是"一半以上的题目框被压住"。
#: 门槛放低（比如 0.2），随便一张只有页眉被识别出来的白卷也会配上某张模板页，
#: 教师拿到的题号归属会整体错位；门槛抬高，学生整题漏答（题框空着没有区域）时
#: 反而判成"没对上模板"，丢掉的是整页的题号。0.5 是这两种误判之间的折中。
ALIGNMENT_MATCH_THRESHOLD = 0.5


class OcrPipelineError(RuntimeError):
    """本服务对外暴露的错误基类。API 层直接采用 ``status_code`` 与 ``code``。"""

    status_code = 500
    code = "OCR_INTERNAL"


class ImageDecodeError(OcrPipelineError):
    """请求里的页面图像无法解码——这是调用方的问题，不是服务故障。"""

    status_code = 422
    code = "IMAGE_DECODE_FAILED"


class EngineUnavailableError(OcrPipelineError):
    """识别引擎不可用（未安装、权重缺失、GPU 不可用）。可重试。"""

    status_code = 503
    code = "ENGINE_UNAVAILABLE"


class LayoutDetectionError(OcrPipelineError):
    """版面检测返回了无法使用的结果。不可重试，需要人工介入。"""

    status_code = 500
    code = "LAYOUT_DETECTION_FAILED"


@dataclass(frozen=True)
class PreprocessedPage:
    """处理中的一页。

    ``width``/``height`` 是**原始解码尺寸**，会原样进响应；``image`` 是可能缩小过的处理副本。
    两者分开是因为识别在副本上做，而调用方拿到的尺寸要对得上它自己存的那张页面图。
    """

    page_no: int
    width: int
    height: int
    image: np.ndarray

    @property
    def processing_width(self) -> int:
        return int(self.image.shape[1])

    @property
    def processing_height(self) -> int:
        return int(self.image.shape[0])


@dataclass(frozen=True)
class RawRecognition:
    """引擎对一个区域的识别输出。"""

    text: str | None
    latex: str | None
    confidence: float


@dataclass(frozen=True)
class DetectedRegion:
    """检测到的区域，坐标是处理副本上的像素值。"""

    left: float
    top: float
    right: float
    bottom: float
    kind: str
    #: 检测阶段顺带给出的识别结果。
    #:
    #: 文本检测器（PaddleOCR 的默认模式就是）一次调用就能拿到框、文字与置信度，
    #: 这种情况下把结果带在这里，``recognize_region`` 就直接采用；
    #: 只有需要单独裁剪重跑的引擎（版面模型 + 独立文字识别）才留空走 ``engine.recognize``。
    #: 同一块区域识别两遍不仅浪费算力，还可能给出不一致的文本。
    recognition: "RawRecognition | None" = None


@dataclass(frozen=True)
class RecognizedRegion:
    external_id: str
    type: str
    x: float
    y: float
    width: float
    height: float
    text: str | None
    latex: str | None
    confidence: float
    #: 配准阶段回填的题号。默认 None，识别阶段不关心它。
    question_code: str | None = None


@dataclass(frozen=True)
class RecognizedPage:
    page_no: int
    width: int
    height: int
    regions: list[RecognizedRegion]
    #: 配准阶段回填。默认值让识别阶段（以及只测识别的既有测试）不必构造它们。
    template_page_no: int | None = None
    alignment_confidence: float | None = None


class OcrEngine(Protocol):
    """识别引擎协议。生产实现是 PaddleOCR，测试实现是固定输出的假引擎。"""

    name: str
    model_version: str

    def detect(self, image: np.ndarray) -> Sequence[DetectedRegion]:
        """检测版面区域。"""

    def recognize(self, image: np.ndarray, region: DetectedRegion) -> RawRecognition:
        """识别单个区域内的文字或公式。"""


def decode_page(content_base64: str, page_no: int) -> np.ndarray:
    """把请求里的 base64 页面图解码成 RGB 数组。"""
    try:
        raw = base64.b64decode(content_base64, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ImageDecodeError(f"第 {page_no} 页不是合法的 base64") from error
    try:
        with Image.open(io.BytesIO(raw)) as opened:
            return np.asarray(opened.convert("RGB"))
    except (UnidentifiedImageError, OSError) as error:
        raise ImageDecodeError(f"第 {page_no} 页无法解码") from error


def preprocess_page(image: np.ndarray, page_no: int) -> PreprocessedPage:
    """记录原始尺寸，必要时生成缩小副本供识别使用。"""
    if image.ndim != 3 or image.shape[2] != 3:
        raise ImageDecodeError(f"第 {page_no} 页不是三通道图像")
    height, width = int(image.shape[0]), int(image.shape[1])
    if height <= 0 or width <= 0:
        raise ImageDecodeError(f"第 {page_no} 页尺寸无效")
    longest_edge = max(height, width)
    if longest_edge <= MAX_PROCESSING_EDGE:
        return PreprocessedPage(page_no, width, height, image)
    scaled = Image.fromarray(image).resize(
        (max(1, round(width * MAX_PROCESSING_EDGE / longest_edge)),
         max(1, round(height * MAX_PROCESSING_EDGE / longest_edge))),
        Image.LANCZOS,
    )
    return PreprocessedPage(page_no, width, height, np.asarray(scaled))


def detect_layout(page: PreprocessedPage, engine: OcrEngine) -> list[DetectedRegion]:
    """检测版面区域，并按阅读顺序排序。

    零面积框在这里就丢掉：它归一化之后宽或高会变成 0，裁剪出来是一张空图，
    而教师看到的只是"这里有个框但什么都没有"。
    """
    try:
        detected = list(engine.detect(page.image))
    except OcrPipelineError:
        raise
    except Exception as error:  # 引擎内部异常统一映射，避免把栈信息透给调用方
        raise LayoutDetectionError(f"第 {page.page_no} 页版面检测失败") from error
    usable = [region for region in detected
              if region.right > region.left and region.bottom > region.top]
    return sorted(usable, key=lambda region: (region.top, region.left))


def recognize_region(page: PreprocessedPage, region: DetectedRegion, engine: OcrEngine,
                     order: int) -> RecognizedRegion:
    """识别单个区域，并把像素坐标归一化到 0..1。

    归一化用**处理副本**的尺寸：检测是在副本上做的，用原始尺寸去除会让框整体偏小。
    """
    if region.recognition is not None:
        raw = region.recognition
    else:
        try:
            raw = engine.recognize(page.image, region)
        except OcrPipelineError:
            raise
        except Exception as error:
            raise LayoutDetectionError(f"第 {page.page_no} 页区域识别失败") from error
    return RecognizedRegion(
        external_id=f"p{page.page_no}-r{order}",
        type=region.kind,
        x=_normalize(region.left, page.processing_width),
        y=_normalize(region.top, page.processing_height),
        width=_normalize(region.right - region.left, page.processing_width),
        height=_normalize(region.bottom - region.top, page.processing_height),
        text=raw.text,
        latex=raw.latex,
        confidence=_clamp_confidence(raw.confidence),
    )


def align_to_template(pages: Sequence[RecognizedPage],
                      template: TemplateInput | None) -> list[RecognizedPage]:
    """把每张提交页配到模板页上，并给区域标注题号。

    判定只基于**归一化框的重叠度**，是纯几何的：模板页上每个题目框被提交页区域压住的
    比例越高，越像"这一页就是它"。没有模板（或模板里没有页）时原样返回，
    三个配准字段都保持 None——"没比对过"和"比对了但不像"对调用方是两件事，
    前者不该出现在教师端，后者要提示"这批答卷没对上模板"。

    多个提交页可以对上同一张模板页：重复页是业务侧的事（学生可能重复扫描），
    这一层不替它拦，拦了反而会让一份来重复页的答卷整体识别失败。
    """
    if template is None or not template.pages:
        return list(pages)
    return [_align_page(page, template.pages) for page in pages]


def _align_page(page: RecognizedPage, template_pages: Sequence[TemplatePageInput]) -> RecognizedPage:
    """单页配准：挑得分最高的模板页，够阈值才认，认了才给区域定题号。"""
    best_page, best_score = max(
        ((candidate, _coverage_score(page.regions, candidate.questions))
         for candidate in template_pages),
        key=lambda pair: pair[1],
    )
    if best_score < ALIGNMENT_MATCH_THRESHOLD:
        # 比过但不像：只回报得分，不认模板页，也不给题号。
        # 给一个"猜的"题号比空着更危险——教师会把非空题号当成已确定的归属。
        return replace(page, alignment_confidence=best_score)
    return replace(
        page,
        template_page_no=best_page.pageNo,
        alignment_confidence=best_score,
        regions=[replace(region, question_code=_best_question_code(region, best_page.questions))
                 for region in page.regions],
    )


def _coverage_score(regions: Sequence[RecognizedRegion],
                    questions: Sequence[QuestionBoxInput]) -> float:
    """模板页的匹配得分：各题目框被压住比例的平均值。"""
    if not questions:
        # 空模板页没有可比对的东西。用空列表求均值会直接抛异常，而它在语义上就是 0 分：
        # 没有任何题目框，也就压不住任何区域，不构成"这一页是它"的任何证据。
        return 0.0
    covered = [_covered_fraction(question, regions) for question in questions]
    # 决定用的数就是上报给调用方的数：两边取同一份四舍五入的值，
    # 排查"为什么这页没配上"时，日志里的得分和判定依据才对得上。
    return round(sum(covered) / len(covered), NORMALIZED_DECIMALS)


def _covered_fraction(question: QuestionBoxInput, regions: Sequence[RecognizedRegion]) -> float:
    """单个题目框被压住的比例：所有区域与它的相交面积之和，除以题目框面积，夹紧到 1。

    这是"并集覆盖"的近似，不是取最大的那一块：一道题的作答通常不止一块，
    手写答卷经过文本检测后一行一块、公式再单独一块，每块只压住题框的一部分。
    只取最大块的话，一道题被三块各压 1/3 也只得 0.33，几道题下来整页就落到阈值以下，
    一张完全正常的答卷会被判成"对不上模板"。教师发现警告几乎每页都出现之后就会无视它，
    那这个警告等于没有——多认一页的代价（题号归属错了，教师改一下）远小于此。

    这里**明确容忍区域互相重叠时被重复计入**：这个问题问的是"这个题框看起来被盖住了吗"，
    不是"盖住的精确面积是多少"。夹紧到 1 之后，重叠只会让判定更偏向"盖住了"，
    方向与上面那笔取舍一致，所以不必为了去重引入面积合并这类更重的计算。
    """
    area = question.width * question.height
    if area <= 0:
        # 零面积题框除不了，也压不住任何东西。记 0 而不是跳过——跳过会让均值悄悄变高。
        return 0.0
    covered = sum(_intersection_area(question, region) for region in regions)
    return min(1.0, max(0.0, covered / area))


def _intersection_area(a: QuestionBoxInput | RecognizedRegion,
                       b: QuestionBoxInput | RecognizedRegion) -> float:
    """两个归一化框的相交面积。不相交（含只贴边）时是 0。"""
    overlap_width = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
    overlap_height = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
    if overlap_width <= 0 or overlap_height <= 0:
        return 0.0
    return overlap_width * overlap_height


def _best_question_code(region: RecognizedRegion,
                        questions: Sequence[QuestionBoxInput]) -> str | None:
    """区域归属的题号：相交面积最大的题目框。完全不相交则不给（None）。

    压在两个题框上的歧义**不在这里处理**：跨栏、跨页的题框本来就该由业务侧判定
    并给教师一句警告，Python 只给最优解。并列时取先出现的那个，保证同一份输入的结果可复现。
    """
    best_code: str | None = None
    best_area = 0.0
    for question in questions:
        area = _intersection_area(region, question)
        if area > best_area:
            best_code, best_area = question.questionCode, area
    return best_code


def normalize_result(pages: Sequence[RecognizedPage], engine: OcrEngine) -> AnalyzeResponse:
    """把各页识别结果组装成响应。"""
    if not pages:
        raise LayoutDetectionError("没有任何页面可返回")
    return AnalyzeResponse(
        schemaVersion=SCHEMA_VERSION,
        engine=engine.name,
        modelVersion=engine.model_version,
        pages=[Page(
            pageNo=page.page_no,
            width=page.width,
            height=page.height,
            templatePageNo=page.template_page_no,
            alignmentConfidence=page.alignment_confidence,
            # 恒为 None：真实的透视配准要图像级模型（模板页与答卷页做特征匹配、解 3x3 单应矩阵），
            # 本阶段只有归一化框的重叠度判定。这里留空而不是塞一个单位矩阵——
            # 伪造的矩阵会让下游以为"已经做过透视校正"，拿它去反算坐标只会把框算歪。
            transformMatrix=None,
            regions=[Region(
                externalId=region.external_id,
                type=region.type,
                x=region.x,
                y=region.y,
                width=region.width,
                height=region.height,
                text=region.text,
                latex=region.latex,
                confidence=region.confidence,
                questionCode=region.question_code,
            ) for region in page.regions],
        ) for page in pages],
    )


def analyze_document(request: AnalyzeRequest, engine: OcrEngine) -> AnalyzeResponse:
    """串起四个阶段，处理一份文档的全部页面。"""
    recognized: list[RecognizedPage] = []
    for page_input in request.pages:
        image = decode_page(page_input.contentBase64, page_input.pageNo)
        page = preprocess_page(image, page_input.pageNo)
        detected = detect_layout(page, engine)
        regions = [recognize_region(page, region, engine, order)
                   for order, region in enumerate(detected, start=1)]
        recognized.append(RecognizedPage(page.page_no, page.width, page.height, regions))
    # 配准放在归一化之后：它吃的是 0..1 的框，直接在这个坐标系里算重叠，
    # 不必再关心每页的处理副本尺寸是否被缩放过。
    aligned = align_to_template(recognized, request.template)
    return normalize_result(aligned, engine)


def _normalize(value: float, total: int) -> float:
    """像素换算成 0..1 相对值。

    检测器偶尔会给出略微越界的框，这里夹到区间内：因为 0.0005 的越界把整份文档判为
    契约不合法，代价远大于收益——真正荒谬的坐标会被 0..1 的契约校验在后一步拦住。
    """
    if total <= 0:
        return 0.0
    clamped = min(1.0, max(0.0, value / total))
    return round(clamped, NORMALIZED_DECIMALS)


def _clamp_confidence(value: float) -> float:
    """置信度统一到 0..1。引擎给出的负值或超过 1 的值属于实现细节，不该让整份结果作废。"""
    if not np.isfinite(value):
        return 0.0
    return round(min(1.0, max(0.0, float(value))), NORMALIZED_DECIMALS)
