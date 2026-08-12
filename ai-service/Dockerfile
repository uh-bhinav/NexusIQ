# Functional multi-stage image for Phase 11's CI docker-build stage.
# Known, deliberately deferred to Phase 12 ("slim" is an explicit Phase 12
# acceptance criterion, not Phase 11's): this image is large (~8GB) because
# torch — a transitive dependency of sentence-transformers, needed only for
# local CPU embedding inference — resolves to a CUDA-bundled wheel with
# ~3GB of nvidia/triton packages this project never uses (no GPU story
# anywhere in .claude/rules/ai-service.md). Tried pinning torch to the
# official CPU-only wheel index via [tool.uv.sources] in pyproject.toml;
# confirmed via `uv lock -v` that uv still resolved torch's metadata from
# the default PyPI index regardless (a real uv behavior worth investigating
# further, not a mistake papered over — reverted rather than shipping unused
# config). Revisit in Phase 12 alongside pre-baking the embedding model
# (avoiding the first-request HuggingFace download noted in STATUS.md's
# technical debt).

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
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
