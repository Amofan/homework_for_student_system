"""契约校验：请求与响应都必须拒绝"看起来能解析、实际会让教师看到错位结果"的数据。

重点覆盖 NaN：``ge=0``/``le=1`` 是拦不住 NaN 的（NaN 与任何数比较都是 False），
必须靠 ``allow_inf_nan=False``。这一条如果漏了，识别出的坐标会一路走到裁剪，
最后变成一张空白题图，而界面上不会报任何错。
"""

from __future__ import annotations

import copy
from typing import Any

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.contracts import AnalyzeRequest, AnalyzeResponse
from app.main import create_app
from app.pipeline import DetectedRegion, OcrEngine, RawRecognition

TOKEN = "test-internal-token"


def valid_request() -> dict[str, Any]:
    return {
        "schemaVersion": "v1",
        "documentKind": "EXAM_PAPER",
        "processingVersion": 1,
        "pages": [{"pageNo": 1, "contentBase64": "AAAA"}],
    }


def valid_response() -> dict[str, Any]:
    return {
        "schemaVersion": "v1",
        "engine": "paddleocr",
        "modelVersion": "3.7.0",
        "pages": [{
            "pageNo": 1,
            "width": 2480,
            "height": 3508,
            "regions": [{
                "externalId": "p1-r1",
                "type": "QUESTION_TEXT",
                "x": 0.10,
                "y": 0.20,
                "width": 0.70,
                "height": 0.08,
                "text": "如图，AB∥CD…",
                "latex": None,
                "confidence": 0.93,
            }],
        }],
    }


def request_with_template() -> dict[str, Any]:
    """带模板几何的请求：两页模板、第一页两道题。"""
    payload = valid_request()
    payload["template"] = {
        "pages": [
            {"pageNo": 1, "questions": [
                {"questionCode": "1", "x": 0.10, "y": 0.20, "width": 0.80, "height": 0.10},
                {"questionCode": "17", "x": 0.10, "y": 0.40, "width": 0.80, "height": 0.10},
            ]},
            {"pageNo": 2, "questions": [
                {"questionCode": "18", "x": 0.10, "y": 0.20, "width": 0.80, "height": 0.10},
            ]},
        ],
    }
    return payload


class _FakeEngine:
    name = "fake"
    model_version = "test-1.0"

    def detect(self, image):  # noqa: ANN001, ANN201 - 测试替身，签名无关紧要
        return [DetectedRegion(0, 0, 10, 10, "TEXT_BLOCK",
                               RawRecognition("题干", None, 0.9))]

    def recognize(self, image, region):  # noqa: ANN001, ANN201
        return RawRecognition("题干", None, 0.9)


def _engine() -> OcrEngine:
    return _FakeEngine()  # type: ignore[return-value]


def test_合法请求与响应都能通过校验() -> None:
    assert AnalyzeRequest(**valid_request()).documentKind == "EXAM_PAPER"
    assert AnalyzeResponse(**valid_response()).schemaVersion == "v1"


def test_未知schema版本被拒绝() -> None:
    payload = valid_request()
    payload["schemaVersion"] = "v2"

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


def test_未知文档类型被拒绝() -> None:
    payload = valid_request()
    payload["documentKind"] = "SOMETHING_ELSE"

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


def test_空页面数组被拒绝() -> None:
    payload = valid_request()
    payload["pages"] = []

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


def test_多余字段被拒绝() -> None:
    payload = valid_request()
    payload["unexpected"] = True

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


@pytest.mark.parametrize("field", ["x", "y", "width", "height", "confidence"])
@pytest.mark.parametrize("value", [float("nan"), float("inf"), -float("inf")])
def test_NaN与无穷被拒绝(field: str, value: float) -> None:
    payload = valid_response()
    payload["pages"][0]["regions"][0][field] = value

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


@pytest.mark.parametrize("field", ["x", "y", "width", "height", "confidence"])
@pytest.mark.parametrize("value", [-0.01, 1.01])
def test_越界取值被拒绝(field: str, value: float) -> None:
    payload = valid_response()
    payload["pages"][0]["regions"][0][field] = value

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


def test_同一页内重复的区域标识被拒绝() -> None:
    payload = valid_response()
    payload["pages"][0]["regions"].append(copy.deepcopy(payload["pages"][0]["regions"][0]))

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


def test_跨页重复的区域标识被拒绝() -> None:
    payload = valid_response()
    second_page = copy.deepcopy(payload["pages"][0])
    second_page["pageNo"] = 2
    payload["pages"].append(second_page)

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


def test_允许整页没有区域() -> None:
    payload = valid_response()
    payload["pages"][0]["regions"] = []

    assert AnalyzeResponse(**payload).pages[0].regions == []


def test_合法请求可以带模板几何() -> None:
    parsed = AnalyzeRequest(**request_with_template())

    assert parsed.template is not None
    assert [page.pageNo for page in parsed.template.pages] == [1, 2]
    assert [question.questionCode for question in parsed.template.pages[0].questions] == ["1", "17"]
    assert parsed.template.pages[0].questions[0].width == 0.80


def test_不带模板时模板字段为空() -> None:
    # 模板可选：还没做模板功能的调用方不改一行也能继续用。
    assert AnalyzeRequest(**valid_request()).template is None


def test_空模板页列表等价于没有模板() -> None:
    payload = valid_request()
    payload["template"] = {"pages": []}

    parsed = AnalyzeRequest(**payload)

    assert parsed.template is not None
    assert parsed.template.pages == []


def test_模板页允许没有题目框() -> None:
    # 模板可能只登记了"这一页存在"还没画框，这种页在配准里记 0 分而不是判成契约不合法。
    payload = valid_request()
    payload["template"] = {"pages": [{"pageNo": 1, "questions": []}]}

    assert AnalyzeRequest(**payload).template.pages[0].questions == []  # type: ignore[union-attr]


def test_模板页号重复被拒绝() -> None:
    payload = request_with_template()
    payload["template"]["pages"][1]["pageNo"] = 1

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


def test_模板页号必须从1开始() -> None:
    payload = valid_request()
    payload["template"] = {"pages": [{"pageNo": 0, "questions": []}]}

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


def test_同一模板页内题号重复被拒绝() -> None:
    payload = request_with_template()
    payload["template"]["pages"][0]["questions"].append(
        copy.deepcopy(payload["template"]["pages"][0]["questions"][0]))

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


@pytest.mark.parametrize("code", ["", "题" * 65])
def test_题号为空或过长被拒绝(code: str) -> None:
    payload = request_with_template()
    payload["template"]["pages"][0]["questions"][0]["questionCode"] = code

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


@pytest.mark.parametrize("field", ["x", "y", "width", "height"])
@pytest.mark.parametrize("value", [float("nan"), float("inf"), -float("inf")])
def test_模板框坐标的NaN与无穷被拒绝(field: str, value: float) -> None:
    # 模板坐标与识别坐标共用 UnitFraction：NaN 的坑只需要在一个地方堵。
    payload = request_with_template()
    payload["template"]["pages"][0]["questions"][0][field] = value

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


@pytest.mark.parametrize("field", ["x", "y", "width", "height"])
@pytest.mark.parametrize("value", [-0.01, 1.01])
def test_模板框坐标越界被拒绝(field: str, value: float) -> None:
    payload = request_with_template()
    payload["template"]["pages"][0]["questions"][0][field] = value

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


@pytest.mark.parametrize("path", [
    ("template",),
    ("template", "pages", 0),
    ("template", "pages", 0, "questions", 0),
])
def test_模板每一层的未知字段都被拒绝(path: tuple[Any, ...]) -> None:
    # extra="forbid" 必须覆盖到 template 这一层：模板框直接决定题号归属，
    # 多出来的字段被静默丢掉，只会在教师端表现为"题号对不上"。
    payload = request_with_template()
    node = payload
    for step in path:
        node = node[step]
    node["unexpected"] = True

    with pytest.raises(ValidationError):
        AnalyzeRequest(**payload)


def test_合法响应可以带配准结果() -> None:
    payload = valid_response()
    payload["pages"][0]["templatePageNo"] = 1
    payload["pages"][0]["alignmentConfidence"] = 0.82
    payload["pages"][0]["regions"][0]["questionCode"] = "17"

    parsed = AnalyzeResponse(**payload)

    assert parsed.pages[0].templatePageNo == 1
    assert parsed.pages[0].alignmentConfidence == 0.82
    assert parsed.pages[0].regions[0].questionCode == "17"


def test_没有配准的页面三个字段都为空() -> None:
    # 没带模板时 Python 侧就是这样回的：Java 侧靠 alignmentConfidence 是否为 None
    # 区分"没比对过"和"比对了但不像"。
    page = AnalyzeResponse(**valid_response()).pages[0]

    assert page.templatePageNo is None
    assert page.alignmentConfidence is None
    assert page.transformMatrix is None
    assert page.regions[0].questionCode is None


def test_配上了页但区域不属于任何题目时题号为空() -> None:
    payload = valid_response()
    payload["pages"][0]["templatePageNo"] = 1
    payload["pages"][0]["alignmentConfidence"] = 0.0

    parsed = AnalyzeResponse(**payload)

    # 得分 0 也是"比过"的证据，必须原样带出来；题号则空着，不给猜的归属。
    assert parsed.pages[0].alignmentConfidence == 0.0
    assert parsed.pages[0].regions[0].questionCode is None


@pytest.mark.parametrize("value", [float("nan"), float("inf"), -float("inf"), -0.01, 1.01])
def test_配准置信度的非法取值被拒绝(value: float) -> None:
    payload = valid_response()
    payload["pages"][0]["alignmentConfidence"] = value

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


@pytest.mark.parametrize("value", [0, -1])
def test_模板页号必须从1开始_响应侧(value: int) -> None:
    payload = valid_response()
    payload["pages"][0]["templatePageNo"] = value

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


@pytest.mark.parametrize("code", ["", "题" * 65])
def test_响应里的题号为空或过长被拒绝(code: str) -> None:
    payload = valid_response()
    payload["pages"][0]["regions"][0]["questionCode"] = code

    with pytest.raises(ValidationError):
        AnalyzeResponse(**payload)


def test_响应里允许出现配准矩阵字段() -> None:
    # 字段本身是契约的一部分（将来接真实透视配准模型时要用），只是本阶段服务端恒填 None。
    payload = valid_response()
    payload["pages"][0]["transformMatrix"] = "1 0 0 0 1 0 0 0 1"

    assert AnalyzeResponse(**payload).pages[0].transformMatrix == "1 0 0 0 1 0 0 0 1"


def test_健康检查不需要令牌且不加载模型() -> None:
    client = TestClient(create_app(engine=_engine(), token=TOKEN))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "engine": "fake", "tokenConfigured": True}


def test_未带令牌的识别请求被拒绝() -> None:
    client = TestClient(create_app(engine=_engine(), token=TOKEN))

    response = client.post("/v1/documents/analyze", json=valid_request())

    assert response.status_code == 401


def test_未配置令牌时拒绝处理识别请求() -> None:
    client = TestClient(create_app(engine=_engine(), token=""))

    response = client.post("/v1/documents/analyze", json=valid_request(),
                           headers={"Authorization": "Bearer anything"})

    # 失败关闭：没配令牌就不处理，而不是"当作没要求令牌"。
    assert response.status_code == 503


def test_契约不合法的请求返回422() -> None:
    client = TestClient(create_app(engine=_engine(), token=TOKEN))
    payload = valid_response()
    payload["pages"][0]["regions"][0]["x"] = 1.5

    response = client.post("/v1/documents/analyze", json=payload,
                           headers={"Authorization": f"Bearer {TOKEN}"})
    response_for_bad_schema = client.post(
        "/v1/documents/analyze",
        json={**valid_request(), "schemaVersion": "v9"},
        headers={"Authorization": f"Bearer {TOKEN}"},
    )

    assert response.status_code == 422
    assert response_for_bad_schema.status_code == 422


def test_接口清单不对外暴露() -> None:
    app = create_app(engine=_engine(), token=TOKEN)

    # 这个服务会收到学生答卷图像，没有理由把接口文档挂出来。
    assert app.docs_url is None
    assert app.openapi_url is None
