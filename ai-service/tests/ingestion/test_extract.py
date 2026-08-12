from pathlib import Path

import pytest

from app.ingestion.extract import (
    ExtractionError,
    extract,
    extract_docx,
    extract_md,
    extract_pdf,
    extract_txt,
)

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def test_extract_pdf_capturesPagesAndHeadings():
    doc = extract_pdf(FIXTURES / "sample_policy.pdf")

    assert doc.format == "pdf"
    page_numbers = {b.page_number for b in doc.blocks}
    assert page_numbers == {1, 2}

    headings = [b for b in doc.blocks if b.is_heading]
    assert any("Data Residency Requirements" in h.text for h in headings)
    assert any(h.heading_level == 1 for h in headings)
    assert any(h.heading_level == 2 for h in headings)


def test_extract_docx_usesHeadingStyles():
    doc = extract_docx(FIXTURES / "sample_policy.docx")

    assert doc.format == "docx"
    headings = [b for b in doc.blocks if b.is_heading]
    assert [h.text for h in headings] == [
        "Vendor Risk Assessment Standard",
        "Sub-processor Disclosure",
        "Escalation Trigger",
    ]
    assert headings[0].heading_level == 1
    assert headings[1].heading_level == 2


def test_extract_txt_usesNumberedHeadingHeuristic():
    doc = extract_txt(FIXTURES / "sample_policy.txt")

    assert doc.format == "txt"
    headings = [b for b in doc.blocks if b.is_heading]
    assert len(headings) == 2
    assert headings[0].heading_level == 1
    assert headings[1].heading_level == 2


def test_extract_md_usesMarkdownHeadingSyntax():
    doc = extract_md(FIXTURES / "sample_policy.md")

    assert doc.format == "md"
    headings = [b for b in doc.blocks if b.is_heading]
    assert headings[0].text == "Vendor Security Standard"
    assert headings[0].heading_level == 1
    assert headings[1].heading_level == 2


def test_extract_corruptPdf_raisesExtractionError():
    with pytest.raises(ExtractionError):
        extract_pdf(FIXTURES / "corrupt.pdf")


def test_extract_emptyTxt_raisesExtractionError():
    with pytest.raises(ExtractionError):
        extract_txt(FIXTURES / "empty.txt")


def test_extract_dispatchesByDeclaredFormat():
    doc = extract(FIXTURES / "sample_policy.md", "md")
    assert doc.format == "md"


def test_extract_unsupportedFormat_raisesExtractionError():
    with pytest.raises(ExtractionError):
        extract(FIXTURES / "sample_policy.md", "exe")
