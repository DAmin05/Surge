from fastapi.testclient import TestClient

from surge_payment.app import app


def test_health_is_up() -> None:
    res = TestClient(app).get("/health")
    assert res.status_code == 200
    assert res.json() == {"status": "UP"}


def test_metrics_are_exposed() -> None:
    res = TestClient(app).get("/metrics")
    assert res.status_code == 200
    assert "python_info" in res.text


def test_starts_with_otel_export_configured(monkeypatch) -> None:
    # Compose sets this for every service; startup must not fail because of it.
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318")
    monkeypatch.setenv("OTEL_METRICS_EXPORTER", "none")
    monkeypatch.setenv("OTEL_LOGS_EXPORTER", "none")
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
