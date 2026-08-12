"""Hierarchical chunker: section/page-aware, with paragraph-level overlap
across chunk boundaries within the same section (roadmap Phase 2 — chunk
boundaries destroying policy-section semantics is the risk called out there).

`token_count` is a word-count approximation, not a real tokenizer count — good
enough for chunk-sizing decisions here; RAG's token *budget* (Phase 3) is a
separate, more careful accounting concern.
"""

from app.models.ingestion import Chunk, ExtractedBlock, ExtractedDocument

# Soft target / hard cap in words per chunk, and how much of the last
# paragraph carries forward into the next chunk as overlap context.
_TARGET_WORDS = 150
_MAX_WORDS = 250


class _Paragraph:
    __slots__ = ("text", "page_number")

    def __init__(self, text: str, page_number: int | None):
        self.text = text
        self.page_number = page_number


class _Section:
    __slots__ = ("heading_path", "section", "subsection", "paragraphs")

    def __init__(self, heading_path: list[str], section: str | None, subsection: str | None):
        self.heading_path = heading_path
        self.section = section
        self.subsection = subsection
        self.paragraphs: list[_Paragraph] = []


def _word_count(text: str) -> int:
    return len(text.split())


def _group_into_sections(blocks: list[ExtractedBlock]) -> list[_Section]:
    sections: list[_Section] = []
    heading_stack: list[tuple[int, str]] = []  # (level, text), most specific last
    current = _Section(heading_path=[], section=None, subsection=None)
    sections.append(current)

    for block in blocks:
        if block.is_heading:
            level = block.heading_level or 1
            heading_stack = [h for h in heading_stack if h[0] < level]
            heading_stack.append((level, block.text))

            heading_path = [text for _, text in heading_stack]
            section = heading_stack[0][1] if heading_stack else None
            subsection = heading_stack[1][1] if len(heading_stack) > 1 else None

            current = _Section(heading_path=heading_path, section=section, subsection=subsection)
            sections.append(current)
        else:
            current.paragraphs.append(_Paragraph(block.text, block.page_number))

    return [s for s in sections if s.paragraphs]


def _flush(buffer: list[_Paragraph], section: _Section, chunk_index: int) -> Chunk:
    content = "\n".join(p.text for p in buffer)
    page_number = buffer[0].page_number if buffer else None
    return Chunk(
        chunk_index=chunk_index,
        content=content,
        page_number=page_number,
        section=section.section,
        subsection=section.subsection,
        heading_path=section.heading_path,
        token_count=_word_count(content),
    )


def _chunk_section(section: _Section, start_index: int) -> list[Chunk]:
    chunks: list[Chunk] = []
    buffer: list[_Paragraph] = []
    buffer_words = 0
    index = start_index

    for paragraph in section.paragraphs:
        paragraph_words = _word_count(paragraph.text)

        if buffer and buffer_words + paragraph_words > _MAX_WORDS and buffer_words >= _TARGET_WORDS:
            chunks.append(_flush(buffer, section, index))
            index += 1
            # Carry the last paragraph forward as overlap context.
            overlap = buffer[-1]
            buffer = [overlap]
            buffer_words = _word_count(overlap.text)

        buffer.append(paragraph)
        buffer_words += paragraph_words

    if buffer:
        chunks.append(_flush(buffer, section, index))

    return chunks


def chunk_document(document: ExtractedDocument) -> list[Chunk]:
    sections = _group_into_sections(document.blocks)
    chunks: list[Chunk] = []
    for section in sections:
        chunks.extend(_chunk_section(section, start_index=len(chunks)))
    return chunks
