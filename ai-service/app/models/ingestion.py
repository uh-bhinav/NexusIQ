"""Pydantic models shared by extraction and chunking (app/ingestion/)."""

from pydantic import BaseModel


class ExtractedBlock(BaseModel):
    """One paragraph/line of extracted text, tagged for the chunker."""

    text: str
    page_number: int | None = None
    is_heading: bool = False
    heading_level: int | None = None


class ExtractedDocument(BaseModel):
    format: str
    blocks: list[ExtractedBlock]


class Chunk(BaseModel):
    chunk_index: int
    content: str
    page_number: int | None = None
    section: str | None = None
    subsection: str | None = None
    heading_path: list[str] = []
    token_count: int | None = None
