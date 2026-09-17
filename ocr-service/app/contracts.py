"""与 Java 侧共享的 JSON 契约。

契约的两条硬规则：

1. ``schemaVersion`` 只有 ``v1``。要改字段含义必须换版本号，否则服务端改了含义而调用方无感，
   识别结果会被静默解析成另一种东西——这类错误不会报错，只会让教师看到错位的题框。
2. 所有归一化取值都禁止 NaN 与无穷。NaN 参与的任何比较都是 False，只用 ``ge=0``/``le=1``
   是拦不住它的：NaN 会一路走到裁剪坐标，最后变成一张空白题图，而教师只会觉得"识别坏了"。
"""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = "v1"

DocumentKind = Literal["EXAM_PAPER", "ANSWER_KEY", "STUDENT_SUBMISSION"]

#: 归一化到 0..1 的取值（坐标、置信度）。allow_inf_nan=False 是这一行的重点，见模块说明。
UnitFraction = Annotated[float, Field(ge=0.0, le=1.0, allow_inf_nan=False)]


class PageInput(BaseModel):
    """一页待识别的图像。``pageNo`` 从 1 开始，与页面记录一致。"""

    model_config = ConfigDict(extra="forbid")

    pageNo: int = Field(ge=1)
    contentBase64: str = Field(min_length=1)


class QuestionBoxInput(BaseModel):
    """模板页上一个题目的几何框。坐标与识别结果同一套归一化约定（0..1，相对整页）。

    坐标复用 :data:`UnitFraction` 而不是另写一遍 ``ge=0``/``le=1``：NaN 这个坑只需要在一个地方堵。
    """

    model_config = ConfigDict(extra="forbid")

    questionCode: str = Field(min_length=1, max_length=64)
    x: UnitFraction
    y: UnitFraction
    width: UnitFraction
    height: UnitFraction


class TemplatePageInput(BaseModel):
    """模板里的一页。

    ``pageNo`` 是模板自己的页号，与提交页号不必相同：学生从第 2 页开始写、
    或者扫描时多夹了一张封面，都会让两边页号错开，所以配准按整页几何去找对应关系，
    而不是按页号硬套。
    """

    model_config = ConfigDict(extra="forbid")

    pageNo: int = Field(ge=1)
    # 允许空列表：模板可能只登记了"这一页存在"还没画框。这种模板页压不住任何区域，
    # 在配准里记 0 分（见 pipeline 的 _coverage_score）。
    questions: list[QuestionBoxInput]

    @model_validator(mode="after")
    def _require_unique_question_codes(self) -> "TemplatePageInput":
        # 题号是区域归属的键：同一页里重复的话，"这块区域属于哪一题"就没有唯一答案，
        # 后一个框会静默盖掉前一个，教师看到的题号会随框的顺序变化。
        codes = [question.questionCode for question in self.questions]
        if len(codes) != len(set(codes)):
            raise ValueError("同一模板页内题号必须唯一")
        return self


class TemplateInput(BaseModel):
    """答卷模板的几何信息。空 ``pages`` 与"没带模板"同义，调用方不必先判断列表是否为空。"""

    model_config = ConfigDict(extra="forbid")

    pages: list[TemplatePageInput] = Field(default_factory=list)

    @model_validator(mode="after")
    def _require_unique_page_numbers(self) -> "TemplateInput":
        page_nos = [page.pageNo for page in self.pages]
        if len(page_nos) != len(set(page_nos)):
            raise ValueError("模板页号必须唯一")
        return self


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schemaVersion: Literal["v1"]
    documentKind: DocumentKind
    processingVersion: int = Field(ge=1)
    pages: list[PageInput] = Field(min_length=1)
    # 模板可选：不带模板时服务只做识别，响应里所有配准字段留空。
    # 这样还没做模板功能的调用方不改一行也能继续用，新字段是纯增量。
    template: TemplateInput | None = None


class Region(BaseModel):
    """识别出的一个区域。像素坐标已经在 pipeline 里归一化过。"""

    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1)
    type: str = Field(min_length=1)
    x: UnitFraction
    y: UnitFraction
    width: UnitFraction
    height: UnitFraction
    text: str | None = None
    latex: str | None = None
    confidence: UnitFraction
    # 本区域归属的题号。没做配准、或区域落在所有模板框之外时为 None：
    # 宁可空着也不给"猜"的题号——教师会把非空题号当成已确定的归属。
    questionCode: str | None = Field(default=None, min_length=1, max_length=64)


class Page(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pageNo: int = Field(ge=1)
    width: int = Field(gt=0)
    height: int = Field(gt=0)
    # 允许整页零区域：空白页、只有图形的页、以及学生整页没作答都是真实存在的情况。
    # 强行要求至少一个区域，只会让一张白页把整份文档判成契约不合法。
    regions: list[Region] = Field(default_factory=list)
    # 这一页配上的模板页号。None = 没配上，或请求里根本没带模板。
    templatePageNo: int | None = Field(default=None, ge=1)
    # 配准得分。None = 没做过配准（请求里没带模板）；有值 = 最佳匹配得分，
    # 即使 templatePageNo 为 None 也一定要给——Java 侧靠它区分"没比对过"和"比对了但不像"，
    # 少了它，教师端会把"扫描歪了"误报成"你没上传模板"。
    alignmentConfidence: UnitFraction | None = None
    # 透视配准矩阵。本阶段恒为 None：现在只有归一化框的重叠度判定，没有图像级配准。
    # 字段先占位，等接入真实配准模型时直接填，不必再升 schemaVersion。
    transformMatrix: str | None = None

    @model_validator(mode="after")
    def _require_unique_external_ids(self) -> "Page":
        ids = [region.externalId for region in self.regions]
        if len(ids) != len(set(ids)):
            raise ValueError("同一页内区域标识必须唯一")
        return self


class AnalyzeResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schemaVersion: Literal["v1"] = SCHEMA_VERSION
    engine: str = Field(min_length=1)
    modelVersion: str = Field(min_length=1)
    pages: list[Page] = Field(min_length=1)

    @model_validator(mode="after")
    def _require_unique_external_ids(self) -> "AnalyzeResponse":
        # 跨页也要唯一：externalId 是区域在全文档内的身份，
        # 只在页内唯一的话，后续按 id 回写教师修订会改到另一页的区域上。
        ids = [region.externalId for page in self.pages for region in page.regions]
        if len(ids) != len(set(ids)):
            raise ValueError("文档内区域标识必须唯一")
        return self
