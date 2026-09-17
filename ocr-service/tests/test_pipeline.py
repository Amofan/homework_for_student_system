"""流水线各阶段：坐标归一化、阅读顺序、错误映射。

测试注入固定输出的假引擎，所以这里既不下载权重也不联网——这是"契约测试能进 CI"的前提。
"""

from __future__ import annotations

import base64
import io
from typing import Any, Sequence

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.contracts import AnalyzeRequest, QuestionBoxInput, TemplateInput, TemplatePageInput
from app.main import create_app
from app.pipeline import (
    DetectedRegion,
    EngineUnavailableError,
    ImageDecodeError,
    LayoutDetectionError,
    PreprocessedPage,
    RawRecognition,
    RecognizedPage,
    RecognizedRegion,
    align_to_template,
    analyze_document,
    decode_page,
    detect_layout,
    normalize_result,
    preprocess_page,
    recognize_region,
)

TOKEN = "test-internal-token"


class FakeEngine:
    """固定输出的假引擎。``recognize`` 也可以被显式关掉，用于验证检测结果自带的识别文本被采用。"""

    name = "fake"
    model_version = "test-1.0"

    def __init__(self, regions: Sequence[DetectedRegion], recognize_calls: list[Any] | None = None):
        self.regions = list(regions)
        self.recognize_calls = recognize_calls if recognize_calls is not None else []

    def detect(self, image: np.ndarray) -> Sequence[DetectedRegion]:
        return self.regions

    def recognize(self, image: np.ndarray, region: DetectedRegion) -> RawRecognition:
        self.recognize_calls.append(region)
        return RawRecognition(text="兜底识别", latex=None, confidence=0.5)


class ExplodingEngine(FakeEngine):
    def detect(self, image: np.ndarray) -> Sequence[DetectedRegion]:
        raise ValueError("模型内部错误：不应把栈信息透给调用方")


def png_bytes(width: int, height: int) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (width, height), color=(255, 255, 255)).save(buffer, "PNG")
    return buffer.getvalue()


def encode(image_bytes: bytes) -> str:
    return base64.b64encode(image_bytes).decode("ascii")


def test_像素坐标被归一化到0到1() -> None:
    page = PreprocessedPage(page_no=1, width=1000, height=2000,
                            image=np.zeros((2000, 1000, 3), dtype=np.uint8))
    region = DetectedRegion(100, 200, 200, 300, "TEXT_BLOCK", RawRecognition("题干", None, 0.9))

    recognized = recognize_region(page, region, FakeEngine([]), 1)

    assert recognized.x == 0.1
    assert recognized.y == 0.1
    assert recognized.width == 0.1
    assert recognized.height == 0.05
    assert recognized.external_id == "p1-r1"
    assert recognized.confidence == 0.9


def test_检测结果自带识别文本时不再重复调用识别() -> None:
    engine = FakeEngine([], recognize_calls=[])
    page = PreprocessedPage(1, 100, 100, np.zeros((100, 100, 3), dtype=np.uint8))
    region = DetectedRegion(0, 0, 50, 50, "TEXT_BLOCK", RawRecognition("一次识别", None, 0.8))

    recognized = recognize_region(page, region, engine, 1)

    assert recognized.text == "一次识别"
    # 同一块区域识别两遍既浪费算力，也可能给出不一致的文本，所以这里必须是零次调用。
    assert engine.recognize_calls == []


def test_没有自带文本时回落到引擎识别() -> None:
    engine = FakeEngine([], recognize_calls=[])
    page = PreprocessedPage(1, 100, 100, np.zeros((100, 100, 3), dtype=np.uint8))

    recognized = recognize_region(page, DetectedRegion(0, 0, 50, 50, "TEXT_BLOCK"), engine, 1)

    assert recognized.text == "兜底识别"
    assert len(engine.recognize_calls) == 1


def test_区域按阅读顺序编号并丢弃零面积框() -> None:
    page = PreprocessedPage(1, 100, 100, np.zeros((100, 100, 3), dtype=np.uint8))
    engine = FakeEngine([
        DetectedRegion(10, 50, 60, 60, "TEXT_BLOCK", RawRecognition("第二行", None, 0.9)),
        DetectedRegion(10, 10, 60, 20, "TEXT_BLOCK", RawRecognition("第一行", None, 0.9)),
        DetectedRegion(10, 30, 10, 40, "TEXT_BLOCK", RawRecognition("零宽", None, 0.9)),
    ])

    detected = detect_layout(page, engine)

    assert [region.top for region in detected] == [10, 50]
    assert all(region.right > region.left for region in detected)


def test_越界与非法置信度被夹紧而不是让整份结果作废() -> None:
    page = PreprocessedPage(1, 100, 100, np.zeros((100, 100, 3), dtype=np.uint8))

    recognized = recognize_region(
        page, DetectedRegion(-5, -5, 120, 120, "TEXT_BLOCK", RawRecognition("越界", None, 1.7)), FakeEngine([]), 1)

    assert recognized.x == 0.0
    assert recognized.y == 0.0
    assert recognized.width == 1.0
    assert recognized.confidence == 1.0


def test_超大页面缩小处理但响应保留原始尺寸() -> None:
    image = np.zeros((1000, 5000, 3), dtype=np.uint8)

    page = preprocess_page(image, 1)

    assert (page.width, page.height) == (5000, 1000)
    assert (page.processing_width, page.processing_height) == (4000, 800)
    # 归一化用处理副本的尺寸：检测是在副本上做的，用原始尺寸去除会让框整体偏小。
    recognized = recognize_region(page, DetectedRegion(0, 0, 4000, 800, "TEXT_BLOCK",
                                                       RawRecognition("整页", None, 0.9)), FakeEngine([]), 1)
    assert recognized.width == 1.0
    assert recognized.height == 1.0


def test_引擎内部异常映射为版面检测失败() -> None:
    page = PreprocessedPage(1, 100, 100, np.zeros((100, 100, 3), dtype=np.uint8))

    with pytest.raises(LayoutDetectionError) as error:
        detect_layout(page, ExplodingEngine([]))

    assert error.value.status_code == 500
    assert error.value.code == "LAYOUT_DETECTION_FAILED"
    # 对外消息里不能出现引擎的内部栈信息。
    assert "模型内部错误" not in str(error.value)


def test_无法解码的页面图映射为422() -> None:
    with pytest.raises(ImageDecodeError) as not_base64:
        decode_page("这不是 base64!!!", 1)
    with pytest.raises(ImageDecodeError) as not_image:
        decode_page(encode(b"MZ\x90\x00not-an-image"), 1)

    assert not_base64.value.status_code == 422
    assert not_image.value.status_code == 422


def test_引擎不可用映射为503() -> None:
    assert EngineUnavailableError("未安装").status_code == 503


def test_归一化结果带上引擎与模型版本() -> None:
    from app.pipeline import RecognizedPage

    response = normalize_result(
        [RecognizedPage(1, 100, 100, [])], FakeEngine([]))

    assert response.schemaVersion == "v1"
    assert response.engine == "fake"
    assert response.modelVersion == "test-1.0"
    # 空白页是合法输入：整页没作答或只有图形时，区域列表就是空的。
    assert response.pages[0].regions == []


def test_端到端识别一页并返回契约结构() -> None:
    engine = FakeEngine([
        DetectedRegion(100, 200, 300, 260, "TEXT_BLOCK", RawRecognition("解方程 x=2", None, 0.93)),
    ])
    request = AnalyzeRequest(**{
        "schemaVersion": "v1",
        "documentKind": "EXAM_PAPER",
        "processingVersion": 1,
        "pages": [{"pageNo": 1, "contentBase64": encode(png_bytes(1000, 2000))}],
    })

    response = analyze_document(request, engine)

    page = response.pages[0]
    assert (page.width, page.height) == (1000, 2000)
    assert len(page.regions) == 1
    assert page.regions[0].externalId == "p1-r1"
    assert page.regions[0].x == pytest.approx(0.1)
    assert page.regions[0].height == pytest.approx(0.03)


def test_接口端到端返回识别结果() -> None:
    engine = FakeEngine([
        DetectedRegion(0, 0, 500, 100, "QUESTION_TEXT", RawRecognition("1+1=?", None, 0.91)),
    ])
    client = TestClient(create_app(engine=engine, token=TOKEN))

    response = client.post(
        "/v1/documents/analyze",
        json={
            "schemaVersion": "v1",
            "documentKind": "EXAM_PAPER",
            "processingVersion": 1,
            "pages": [{"pageNo": 1, "contentBase64": encode(png_bytes(1000, 2000))}],
        },
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == "v1"
    assert body["pages"][0]["regions"][0]["text"] == "1+1=?"


def test_接口把解码失败映射成422而非500() -> None:
    client = TestClient(create_app(engine=FakeEngine([]), token=TOKEN))

    response = client.post(
        "/v1/documents/analyze",
        json={
            "schemaVersion": "v1",
            "documentKind": "EXAM_PAPER",
            "processingVersion": 1,
            "pages": [{"pageNo": 1, "contentBase64": encode(b"not an image")}],
        },
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.status_code == 422
    assert response.json()["code"] == "IMAGE_DECODE_FAILED"


# --- 模板配准 ---------------------------------------------------------------


def normalized_region(external_id: str, x: float, y: float,
                      width: float, height: float) -> RecognizedRegion:
    """直接构造归一化后的区域，省掉"造一张图让假引擎检测出来"这一步。"""
    return RecognizedRegion(external_id=external_id, type="TEXT_BLOCK", x=x, y=y, width=width,
                            height=height, text="题干", latex=None, confidence=0.93)


def template_input(page_no: int, **questions: tuple[float, float, float, float]) -> TemplateInput:
    """按 ``题号=(x, y, width, height)`` 构造单页模板。关键字顺序即题框顺序。"""
    return TemplateInput(pages=[TemplatePageInput(pageNo=page_no, questions=[
        QuestionBoxInput(questionCode=code, x=x, y=y, width=width, height=height)
        for code, (x, y, width, height) in questions.items()])])


def test_重叠良好时配上模板页并给区域定题号() -> None:
    page = RecognizedPage(1, 1000, 1400, [
        normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10),  # 与题目 1 的框完全重合
        normalized_region("p1-r2", 0.10, 0.40, 0.80, 0.10),  # 与题目 17 的框完全重合
    ])
    template = template_input(1, **{"1": (0.10, 0.20, 0.80, 0.10), "17": (0.10, 0.40, 0.80, 0.10)})

    aligned = align_to_template([page], template)

    assert aligned[0].template_page_no == 1
    assert aligned[0].alignment_confidence == pytest.approx(1.0)
    assert aligned[0].alignment_confidence > 0.5
    assert [region.question_code for region in aligned[0].regions] == ["1", "17"]


def test_重叠不足时给出得分但不认模板页() -> None:
    page = RecognizedPage(1, 1000, 1400, [normalized_region("p1-r1", 0.10, 0.20, 0.20, 0.05)])
    # 模板框整体右移：只有四分之一压在区域上，达不到"一半以上题目框被压住"的门槛。
    template = template_input(1, **{"1": (0.20, 0.20, 0.40, 0.05)})

    aligned = align_to_template([page], template)

    assert aligned[0].template_page_no is None
    # 得分必须留下：None 表示"没比对过"，0.25 表示"比对过但不像"，Java 侧要区分这两件事。
    assert aligned[0].alignment_confidence == pytest.approx(0.25)
    # 没配上页就不给题号：非空题号会被教师当成已确定的归属。
    assert [region.question_code for region in aligned[0].regions] == [None]


def test_多张模板页里取得分最高的一张() -> None:
    page = RecognizedPage(1, 1000, 1400, [normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10)])
    template = TemplateInput(pages=[
        TemplatePageInput(pageNo=1, questions=[
            QuestionBoxInput(questionCode="1", x=0.60, y=0.70, width=0.30, height=0.10)]),
        TemplatePageInput(pageNo=2, questions=[
            QuestionBoxInput(questionCode="18", x=0.10, y=0.20, width=0.80, height=0.10)]),
    ])

    aligned = align_to_template([page], template)

    assert aligned[0].template_page_no == 2
    assert aligned[0].regions[0].question_code == "18"


def test_多个提交页可以对上同一张模板页() -> None:
    # 重复页是业务侧要处理的事（学生把同一页扫了两遍），配准这一层不替它拦。
    pages = [RecognizedPage(1, 1000, 1400, [normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10)]),
             RecognizedPage(2, 1000, 1400, [normalized_region("p2-r1", 0.10, 0.20, 0.80, 0.10)])]
    template = template_input(1, **{"1": (0.10, 0.20, 0.80, 0.10)})

    aligned = align_to_template(pages, template)

    assert [page.template_page_no for page in aligned] == [1, 1]
    assert [page.regions[0].question_code for page in aligned] == ["1", "1"]


def test_没有模板时配准阶段原样返回() -> None:
    page = RecognizedPage(1, 1000, 1400, [normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10)])

    assert align_to_template([page], None) == [page]
    # 空模板页列表与"没带模板"同义，调用方不必先判断列表是否为空。
    assert align_to_template([page], TemplateInput(pages=[])) == [page]


def test_没有题目框的模板页得分为0而不是报错() -> None:
    page = RecognizedPage(1, 1000, 1400, [normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10)])
    template = TemplateInput(pages=[TemplatePageInput(pageNo=1, questions=[])])

    aligned = align_to_template([page], template)

    # 对空列表求均值会抛异常，而它在语义上就是 0 分：没有任何题目框能构成"这一页是它"的证据。
    assert aligned[0].template_page_no is None
    assert aligned[0].alignment_confidence == 0.0


def test_模板框之外的区域保留但不给题号() -> None:
    page = RecognizedPage(1, 1000, 1400, [
        normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10),  # 压住题目 1
        normalized_region("p1-r2", 0.10, 0.90, 0.20, 0.05),  # 落在所有模板框之外
    ])
    template = template_input(1, **{"1": (0.10, 0.20, 0.80, 0.10)})

    aligned = align_to_template([page], template)

    # 模板框外的区域不能丢：页眉、姓名栏、印刷说明仍然是教师要看的内容。
    assert [region.external_id for region in aligned[0].regions] == ["p1-r1", "p1-r2"]
    assert [region.question_code for region in aligned[0].regions] == ["1", None]
    assert len(aligned[0].regions) == len(align_to_template([page], None)[0].regions)


def test_区域跨两个题框时取相交面积最大的那个() -> None:
    # 压在两个框上的歧义由 Java 侧判定并给教师警告，Python 只给最优解。
    page = RecognizedPage(1, 1000, 1400, [normalized_region("p1-r1", 0.10, 0.20, 0.60, 0.10)])
    template = template_input(1, **{
        "1": (0.10, 0.20, 0.20, 0.10),  # 相交 0.20 × 0.10
        "2": (0.40, 0.20, 0.30, 0.10),  # 相交 0.30 × 0.10，更大
    })

    aligned = align_to_template([page], template)

    assert aligned[0].regions[0].question_code == "2"


def test_题框被多块区域分别压住时按并集累加() -> None:
    # 手写答卷里一道题的作答常被切成好几块（每行一块、公式再一块），每块只压住题框的一部分。
    page = RecognizedPage(1, 1000, 1400, [
        normalized_region("p1-r1", 0.10, 0.20, 0.30, 0.10),  # 压住题目框的左边 1/3
        normalized_region("p1-r2", 0.40, 0.20, 0.30, 0.10),  # 压住题目框的中间 1/3
    ])
    template = template_input(1, **{"1": (0.10, 0.20, 0.90, 0.10)})

    aligned = align_to_template([page], template)

    # 两块各压 1/3，按并集累加是 2/3，够 0.5 的门槛。
    # 如果只取"最大的那一块"（1/3），这张完全正常的答卷就会被判成"对不上模板"。
    assert aligned[0].alignment_confidence == pytest.approx(2 / 3, abs=1e-6)
    assert aligned[0].template_page_no == 1
    assert aligned[0].regions[0].question_code == "1"


def test_区域互相重叠时覆盖比例夹紧到1() -> None:
    page = RecognizedPage(1, 1000, 1400, [
        normalized_region("p1-r1", 0.10, 0.20, 0.80, 0.10),  # 与题目框完全重合
        normalized_region("p1-r2", 0.10, 0.20, 0.80, 0.10),  # 同一个位置的重复检测结果
    ])
    template = template_input(1, **{"1": (0.10, 0.20, 0.80, 0.10)})

    aligned = align_to_template([page], template)

    # 两块重叠着压同一处，相交面积之和是题框面积的两倍。这里**容忍重复计入**：
    # 要回答的是"这个题框看起来被盖住了吗"，夹紧到 1 就够，不必为去重引入面积合并的计算。
    assert aligned[0].alignment_confidence == pytest.approx(1.0)
    assert aligned[0].template_page_no == 1


def request_payload(content_base64: str, template: dict[str, Any] | None = None) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "schemaVersion": "v1",
        "documentKind": "EXAM_PAPER",
        "processingVersion": 1,
        "pages": [{"pageNo": 1, "contentBase64": content_base64}],
    }
    if template is not None:
        payload["template"] = template
    return payload


def test_不带模板时新字段是纯增量() -> None:
    detected = DetectedRegion(100, 140, 900, 280, "TEXT_BLOCK", RawRecognition("解方程 x=2", None, 0.93))
    engine = FakeEngine([detected])
    request = AnalyzeRequest(**request_payload(encode(png_bytes(1000, 1400))))

    response = analyze_document(request, engine)

    # 手工走一遍识别阶段（不经过配准）作为基准：不带模板时配准必须什么都不改。
    page = preprocess_page(decode_page(request.pages[0].contentBase64, 1), 1)
    baseline = normalize_result(
        [RecognizedPage(1, page.width, page.height, [recognize_region(page, detected, engine, 1)])], engine)

    aligned_page = response.pages[0]
    assert aligned_page.templatePageNo is None
    assert aligned_page.alignmentConfidence is None
    assert aligned_page.transformMatrix is None
    assert [region.questionCode for region in aligned_page.regions] == [None]
    # 区域顺序、标识、坐标与文本逐字段一致——新字段是纯增量，老调用方看到的内容不变。
    assert aligned_page.model_dump() == baseline.pages[0].model_dump()


def test_端到端带模板时返回题号归属且配准矩阵为空() -> None:
    engine = FakeEngine([
        DetectedRegion(100, 140, 900, 280, "TEXT_BLOCK", RawRecognition("解方程 x=2", None, 0.93)),
    ])
    request = AnalyzeRequest(**request_payload(encode(png_bytes(1000, 1400)), template={
        "pages": [{"pageNo": 1, "questions": [
            {"questionCode": "1", "x": 0.10, "y": 0.10, "width": 0.80, "height": 0.10}]}],
    }))

    page = analyze_document(request, engine).pages[0]

    assert page.templatePageNo == 1
    assert page.alignmentConfidence == pytest.approx(1.0)
    assert page.regions[0].questionCode == "1"
    # 透视配准矩阵本阶段恒为 None：伪造一个单位矩阵会让下游以为已经做过透视校正。
    assert page.transformMatrix is None


def test_接口端到端返回题号归属() -> None:
    engine = FakeEngine([
        DetectedRegion(100, 140, 900, 280, "TEXT_BLOCK", RawRecognition("解方程 x=2", None, 0.93)),
    ])
    client = TestClient(create_app(engine=engine, token=TOKEN))

    response = client.post(
        "/v1/documents/analyze",
        json=request_payload(encode(png_bytes(1000, 1400)), template={
            "pages": [{"pageNo": 1, "questions": [
                {"questionCode": "1", "x": 0.10, "y": 0.10, "width": 0.80, "height": 0.10}]}],
        }),
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.status_code == 200
    page = response.json()["pages"][0]
    assert page["templatePageNo"] == 1
    assert page["alignmentConfidence"] == pytest.approx(1.0)
    assert page["regions"][0]["questionCode"] == "1"
    assert page["transformMatrix"] is None
