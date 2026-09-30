"""Real backends: the Docker Engine (via the mounted socket), Toxiproxy, and Payment."""

import asyncio
import os
from typing import Any

import docker
import httpx
import redis


class DockerContainers:
    """Compose services by label. Docker's SDK is blocking, so calls run in a thread."""

    def __init__(self, project: str, redis_nodes: list[str]):
        self._client = docker.from_env()
        self._project = project
        self._redis_nodes = redis_nodes

    def _container(self, service: str) -> Any:
        found = self._client.containers.list(
            all=True,
            filters={
                "label": [
                    f"com.docker.compose.project={self._project}",
                    f"com.docker.compose.service={service}",
                ]
            },
        )
        if not found:
            raise LookupError(f"no container for service {service!r}")
        return found[0]

    async def kill(self, service: str) -> str:
        c = await asyncio.to_thread(self._container, service)
        await asyncio.to_thread(c.kill)
        return c.name

    async def start(self, service: str) -> None:
        c = await asyncio.to_thread(self._container, service)
        await asyncio.to_thread(c.start)

    async def restart(self, service: str) -> None:
        c = await asyncio.to_thread(self._container, service)
        await asyncio.to_thread(c.restart, timeout=5)

    async def redis_primary(self) -> str:
        """A node currently serving slots as primary (nodes announce their hostnames)."""

        def find() -> str:
            for node in self._redis_nodes:
                host, port = node.rsplit(":", 1)
                try:
                    lines = redis.Redis(
                        host=host, port=int(port), socket_timeout=2
                    ).execute_command("CLUSTER", "NODES")
                except redis.RedisError:
                    continue
                text = lines.decode() if isinstance(lines, bytes) else str(lines)
                for line in text.splitlines():
                    fields = line.split()
                    if "master" in fields[2] and "fail" not in fields[2] and len(fields) > 8:
                        return fields[1].split(",")[1]  # ip:port@cport,hostname
            raise LookupError("no Redis primary found")

        return await asyncio.to_thread(find)


class HttpToxiproxy:
    def __init__(self, client: httpx.AsyncClient, url: str):
        self._http = client
        self._url = url.rstrip("/")

    async def set_enabled(self, proxy: str, enabled: bool) -> None:
        r = await self._http.post(f"{self._url}/proxies/{proxy}", json={"enabled": enabled})
        r.raise_for_status()

    async def add_latency(self, proxy: str, ms: int) -> None:
        r = await self._http.post(
            f"{self._url}/proxies/{proxy}/toxics",
            json={"name": "chaos_latency", "type": "latency", "attributes": {"latency": ms}},
        )
        r.raise_for_status()

    async def clear(self, proxy: str) -> None:
        r = await self._http.get(f"{self._url}/proxies/{proxy}/toxics")
        r.raise_for_status()
        for toxic in r.json():
            await self._http.delete(f"{self._url}/proxies/{proxy}/toxics/{toxic['name']}")


class HttpPaymentFaults:
    def __init__(self, client: httpx.AsyncClient, url: str):
        self._http = client
        self._url = url.rstrip("/")

    async def set(self, faults: dict[str, Any]) -> None:
        r = await self._http.put(f"{self._url}/faults", json=faults)
        r.raise_for_status()

    async def reset(self) -> None:
        r = await self._http.delete(f"{self._url}/faults")
        r.raise_for_status()


class HttpOrderControl:
    def __init__(self, client: httpx.AsyncClient, url: str):
        self.client = client
        self.url = url.rstrip("/")

    async def set_relay_paused(self, paused: bool) -> None:
        r = await self.client.put(f"{self.url}/internal/outbox-relay", json={"paused": paused})
        r.raise_for_status()


def redis_nodes() -> list[str]:
    return [n for n in os.environ.get("REDIS_NODES", "").split(",") if n]
