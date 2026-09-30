"""HTTP surface for the payment service. Week 0: health and Prometheus metrics."""

from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

app = FastAPI(title="surge-payment")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
