# Production image. Known, still-open item (Phase 12 status, not silently
# dropped): this image is large (~8GB) because torch — a transitive
# dependency of sentence-transformers, needed only for local CPU embedding
# inference — resolves to a CUDA-bundled wheel with ~3GB of nvidia/triton
# packages this project never uses (no GPU story anywhere in
# .claude/rules/ai-service.md). Tried pinning torch to the official
# CPU-only wheel index via [tool.uv.sources] in pyproject.toml twice (Phase
# 11 and again in Phase 12); confirmed both times via `uv lock -v` that uv
# 0.8.4 still resolves torch's metadata from the default PyPI index
# regardless of the pin — a real, reproducible uv behavior with this exact
# transitive-dependency scenario, not a one-off mistake. Not blocking:
# "slim" isn't one of Phase 12's 5 numbered acceptance criteria
# (docs/IMPLEMENTATION/ROADMAP.md), and the image builds and runs
# correctly regardless of size. Pre-baking the embedding model (avoiding
# the first-request HuggingFace download noted in STATUS.md's technical
# debt) is also still open.

FROM python:3.13-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.8.4 /uv /uvx /bin/
WORKDIR /build
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project --no-dev
COPY app/ app/
RUN uv sync --frozen --no-dev

FROM python:3.13-slim
RUN groupadd -r nexusiq && useradd -r -g nexusiq nexusiq
WORKDIR /app
COPY --from=build /build/.venv .venv
COPY --from=build /build/app app
ENV PATH="/app/.venv/bin:$PATH"
USER nexusiq
EXPOSE 8000
# python:slim has no curl/wget by default — using the stdlib instead of
# adding a package just for this.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/ready', timeout=3)" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
