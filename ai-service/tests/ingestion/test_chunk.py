from app.ingestion.chunk import chunk_document
from app.models.ingestion import ExtractedBlock, ExtractedDocument


def _block(
    text: str, *, page: int | None = 1, heading: bool = False, level: int | None = None
) -> ExtractedBlock:
    return ExtractedBlock(text=text, page_number=page, is_heading=heading, heading_level=level)


def test_chunkDocument_respectsSectionAndSubsectionBoundaries():
    doc = ExtractedDocument(
        format="txt",
        blocks=[
            _block("Data Residency Requirements", heading=True, level=1, page=1),
            _block("All vendor systems must store EU data in the EU.", page=1),
            _block("Sub-processor Disclosure", heading=True, level=2, page=1),
            _block("Sub-processors must be disclosed 15 days in advance.", page=1),
            _block("Risk Tier Classification", heading=True, level=1, page=2),
            _block("Tier 1 vendors require quarterly review.", page=2),
        ],
    )

    chunks = chunk_document(doc)

    assert [c.chunk_index for c in chunks] == list(range(len(chunks)))
    assert chunks[0].section == "Data Residency Requirements"
    assert chunks[0].subsection is None
    assert chunks[0].heading_path == ["Data Residency Requirements"]

    assert chunks[1].section == "Data Residency Requirements"
    assert chunks[1].subsection == "Sub-processor Disclosure"
    assert chunks[1].heading_path == ["Data Residency Requirements", "Sub-processor Disclosure"]

    assert chunks[2].section == "Risk Tier Classification"
    assert chunks[2].page_number == 2


def test_chunkDocument_splitsLongSectionsAndCarriesOverlap():
    # One long paragraph per "unit", repeated enough to exceed the chunk's
    # soft target so the section must split into more than one chunk.
    sentence = "This clause describes a vendor security obligation in reasonable detail. "
    paragraph = sentence * 6  # ~66 words
    blocks = [_block("Vendor Obligations", heading=True, level=1)]
    paragraphs = [f"Clause {i}: {paragraph}" for i in range(6)]
    blocks += [_block(p) for p in paragraphs]
    doc = ExtractedDocument(format="txt", blocks=blocks)

    chunks = chunk_document(doc)

    assert len(chunks) > 1
    for chunk in chunks:
        assert chunk.section == "Vendor Obligations"
        assert chunk.token_count is not None and chunk.token_count > 0

    # Overlap: the last paragraph of chunk N should also open chunk N+1.
    first_chunk_paragraphs = chunks[0].content.split("\n")
    second_chunk_paragraphs = chunks[1].content.split("\n")
    assert first_chunk_paragraphs[-1] == second_chunk_paragraphs[0]


def test_chunkDocument_documentWithNoHeadings_isOneImplicitSection():
    doc = ExtractedDocument(
        format="txt",
        blocks=[_block("Just a plain paragraph with no heading structure at all.")],
    )

    chunks = chunk_document(doc)

    assert len(chunks) == 1
    assert chunks[0].section is None
    assert chunks[0].heading_path == []
