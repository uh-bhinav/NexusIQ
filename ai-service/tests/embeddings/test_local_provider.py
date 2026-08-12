from app.embeddings.local import LocalEmbeddingProvider

_MODEL = "BAAI/bge-small-en-v1.5"


def test_embed_isDeterministic():
    provider = LocalEmbeddingProvider(_MODEL, batch_size=8)
    text = "Vendor data must remain within the EU for all regulated workloads."

    first = provider.embed([text])
    second = provider.embed([text])

    assert first == second


def test_embed_returnsCorrectDimensions():
    provider = LocalEmbeddingProvider(_MODEL, batch_size=8)

    vectors = provider.embed(["short text", "another piece of text, a bit longer than the first"])

    assert len(vectors) == 2
    assert all(len(v) == 384 for v in vectors)
    assert provider.dimensions == 384


def test_embed_normalizesVectors():
    provider = LocalEmbeddingProvider(_MODEL, batch_size=8)

    [vector] = provider.embed(["some arbitrary sentence to embed"])
    norm = sum(x * x for x in vector) ** 0.5

    assert abs(norm - 1.0) < 1e-3


def test_embed_emptyList_returnsEmptyList():
    provider = LocalEmbeddingProvider(_MODEL, batch_size=8)
    assert provider.embed([]) == []


def test_embed_similarTextsAreCloserThanDissimilarOnes():
    provider = LocalEmbeddingProvider(_MODEL, batch_size=8)
    anchor, similar, different = provider.embed(
        [
            "Vendor data must remain within the EU for regulated workloads.",
            "EU customer data must be processed and stored inside the EU.",
            "The cafeteria menu changes every Tuesday and Thursday.",
        ]
    )

    def cosine(a: list[float], b: list[float]) -> float:
        return sum(x * y for x, y in zip(a, b, strict=True))

    assert cosine(anchor, similar) > cosine(anchor, different)
