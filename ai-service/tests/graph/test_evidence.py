import uuid

from app.graph.evidence import resolve_evidence_labels

_C1, _C2, _C3 = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()


def test_resolveLabels_validLabels_mapToChunkIdsInOrder():
    resolved = resolve_evidence_labels(["E1", "E3"], [_C1, _C2, _C3])
    assert resolved == [str(_C1), str(_C3)]


def test_resolveLabels_outOfRangeLabel_dropped():
    resolved = resolve_evidence_labels(["E1", "E99"], [_C1, _C2])
    assert resolved == [str(_C1)]


def test_resolveLabels_malformedLabel_dropped():
    resolved = resolve_evidence_labels(["E1", "not-a-label", "Efoo"], [_C1, _C2])
    assert resolved == [str(_C1)]


def test_resolveLabels_emptyInput_returnsEmpty():
    assert resolve_evidence_labels([], [_C1]) == []


def test_resolveLabels_zeroOrNegativeIndex_dropped():
    assert resolve_evidence_labels(["E0", "E-1"], [_C1, _C2]) == []
