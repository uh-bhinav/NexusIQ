from app.llm.pricing import estimate_cost_usd


def test_estimateCost_flatPricedModel_computesFromRates():
    cost = estimate_cost_usd("gemini-2.5-flash", input_tokens=1_000_000, output_tokens=1_000_000)
    assert cost == 0.30 + 2.50


def test_estimateCost_tieredModel_belowThreshold_usesLowRate():
    cost = estimate_cost_usd("gemini-2.5-pro", input_tokens=100_000, output_tokens=1_000_000)
    assert cost == (100_000 / 1_000_000) * 1.25 + 10.00


def test_estimateCost_tieredModel_aboveThreshold_usesHighRate():
    cost = estimate_cost_usd("gemini-2.5-pro", input_tokens=250_000, output_tokens=1_000_000)
    assert cost == (250_000 / 1_000_000) * 2.50 + 15.00


def test_estimateCost_unknownModel_returnsZero_neverFabricatesAPrice():
    assert estimate_cost_usd("mock-gemini-2.5-flash", input_tokens=1000, output_tokens=1000) == 0.0
    assert estimate_cost_usd("some-future-model", input_tokens=1000, output_tokens=1000) == 0.0


def test_estimateCost_zeroTokens_returnsZero():
    assert estimate_cost_usd("gemini-2.5-flash", input_tokens=0, output_tokens=0) == 0.0
