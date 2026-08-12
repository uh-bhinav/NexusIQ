"""Shared single-worker executor for CPU-bound native-code model inference
(SentenceTransformer.encode, CrossEncoder.predict).

Concurrent retrieval tasks (asyncio.gather in agents/retrieval.py, up to
CONTEXT_PLANNER_MAX_TASKS at once) each call embed()/rerank() via
run_in_executor to keep them off the event loop. Running several of those
calls truly concurrently — on separate threads, into the same PyTorch/BLAS
native code — was observed to segfault the process: internal fork-based
parallelism (joblib/loky) collided with concurrent thread execution. See
docs/AI/RAG.md.

Routing every such call through this single-worker executor serializes native
inference process-wide. The call is still off the event loop (run_in_executor
doesn't block async I/O either way), so this trades a little latency under
concurrent retrieval tasks for correctness — cheaper than the alternative of
capping context_planner_max_tasks at 1.
"""

from concurrent.futures import ThreadPoolExecutor

INFERENCE_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="model-inference")
