# 内部 OCR 服务镜像。
#
# PADDLE_EXTRA 决定装 CPU 还是 GPU 运行时：两者的 wheel 互斥，必须分镜像构建，
# 这也是 pyproject 里把它们放进 extras 的原因。
ARG PADDLE_EXTRA=cpu

FROM python:3.13-slim AS runtime

ARG PADDLE_EXTRA

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /srv/ocr

# 先复制声明再装依赖：改业务代码不会让依赖层缓存失效，重建通常只需几秒。
# 依赖版本单一来源于 pyproject，镜像里不再抄一份清单，避免两处漂移。
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir ".[${PADDLE_EXTRA}]"

# 以非 root 运行：这个服务会解析外部传入的图像，进程权限越小越好。
RUN useradd --create-home --uid 10001 ocr && chown -R ocr:ocr /srv/ocr
USER ocr

EXPOSE 8090

# 单 worker：Paddle 模型在进程内占显存/内存，多 worker 会让内存按倍数涨，
# 而且识别是 CPU/GPU 密集的，横向扩展交给容器编排更合适。
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
