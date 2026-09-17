"""内部 OCR 服务入口。

只暴露两个接口：``/health`` 与 ``/v1/documents/analyze``。OpenAPI 文档与
``/docs`` 一律关闭——这是一个接收学生答卷图像的服务，没有理由把接口清单挂出来。

令牌策略是**失败关闭**：``OCR_INTERNAL_TOKEN`` 未配置时直接拒绝所有识别请求。
让服务"没配令牌就先放行"看起来方便，代价是任何能访问内网的人都能把学生答卷推进模型。
"""

from __future__ import annotations

import os
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from .contracts import AnalyzeRequest, AnalyzeResponse
from .pipeline import OcrEngine, OcrPipelineError, analyze_document

TOKEN_ENV = "OCR_INTERNAL_TOKEN"
ENGINE_ENV = "OCR_ENGINE"


def create_app(engine: OcrEngine | None = None, token: str | None = None) -> FastAPI:
    """构造应用。

    引擎与令牌都允许注入，测试因此不需要装 Paddle、也不需要真的配一个令牌。
    """
    app = FastAPI(title="homework-analysis-ocr", docs_url=None, redoc_url=None, openapi_url=None)

    configured_token = token if token is not None else os.environ.get(TOKEN_ENV, "")
    injected_engine = engine
    cached_engine: list[OcrEngine] = []

    def resolve_engine() -> OcrEngine:
        if injected_engine is not None:
            return injected_engine
        if not cached_engine:
            cached_engine.append(_create_default_engine())
        return cached_engine[0]

    def require_token(authorization: str | None = Header(default=None)) -> None:
        if not configured_token:
            raise HTTPException(status_code=503,
                                detail=f"服务未配置 {TOKEN_ENV}，拒绝处理识别请求")
        if authorization != f"Bearer {configured_token}":
            raise HTTPException(status_code=401, detail="内部令牌无效")

    @app.exception_handler(OcrPipelineError)
    async def handle_pipeline_error(_: Request, error: OcrPipelineError) -> JSONResponse:
        # 只回错误码与一句可读说明：不把栈、文件路径与图像内容透给调用方。
        return JSONResponse(status_code=error.status_code,
                            content={"code": error.code, "message": str(error)})

    @app.get("/health")
    def health() -> dict[str, Any]:
        # 健康检查不触发模型加载：否则就绪探针会把"正在加载权重"当成"服务不可用"。
        return {
            "status": "ok",
            "engine": injected_engine.name if injected_engine else _configured_engine_name(),
            "tokenConfigured": bool(configured_token),
        }

    @app.post("/v1/documents/analyze", response_model=AnalyzeResponse,
              dependencies=[Depends(require_token)])
    def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
        return analyze_document(request, resolve_engine())

    return app


def _configured_engine_name() -> str:
    return os.environ.get(ENGINE_ENV, "paddle")


def _create_default_engine() -> OcrEngine:
    """按 ``OCR_ENGINE`` 选择引擎。延迟到第一次识别时才构造。"""
    selected = _configured_engine_name()
    if selected == "paddle":
        from .paddle_engine import PaddleOcrEngine

        return PaddleOcrEngine()
    raise RuntimeError(f"未知的 OCR_ENGINE 取值：{selected}（可选：paddle）")


app = create_app()
