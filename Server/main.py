import asyncio
import os
import re
import time
from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

import uvicorn
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import PlainTextResponse

# --------------------------------------------------------------------------
# Configuration (via environment variables)
# --------------------------------------------------------------------------
PORT = int(os.getenv("PORT", "5000"))
SECRET_TOKEN = os.getenv("SECRET_TOKEN", "")
STALE_SECONDS = int(os.getenv("STALE_SECONDS", "60"))
CLEANUP_INTERVAL = int(os.getenv("CLEANUP_INTERVAL", "30"))

# --------------------------------------------------------------------------
# In-Memory Storage
# --------------------------------------------------------------------------
_lock = asyncio.Lock()
_store: Dict[str, Dict[str, Any]] = {}  # server_id -> {"ts": float, "metrics": {...}}

RESERVED_KEYS = {"server_id"}

# Known metrics -> (Prometheus name, type, HELP text)
METRIC_DEFS = {
    # Performance Metrics
    "tps": ("mc_tps", "gauge", "Current TPS of the Minecraft server (1m average, capped at 20.0)"),
    "mspt": ("mc_mspt", "gauge", "Average tick duration in milliseconds (MSPT)"),
    "cpu_load": ("mc_cpu_load_percent", "gauge", "JVM process CPU load in percent (0.0 - 100.0)"),
    
    # Player Metrics
    "online_players": ("mc_online_players", "gauge", "Online players count"),
    "max_players": ("mc_max_players", "gauge", "Maximum allowed players"),
    
    # Memory Metrics
    "used_memory_mb": ("mc_memory_used_megabytes", "gauge", "Used JVM heap memory in megabytes"),
    "free_memory_mb": ("mc_memory_free_megabytes", "gauge", "Free JVM heap memory in megabytes"),
    "total_memory_mb": ("mc_memory_total_megabytes", "gauge", "Total allocated JVM heap memory in megabytes"),
    
    # World & System Metrics
    "chunks_loaded": ("mc_chunks_loaded", "gauge", "Number of loaded chunks across all worlds"),
    "uptime_seconds": ("mc_uptime_seconds", "gauge", "Server process uptime in seconds"),
    
    # Version Metric (As numeric value, e.g. 1.0.3 -> 1.03)
    "version": ("mc_plugin_version", "gauge", "Minecraft plugin version parsed as float"),
}


def parse_version_to_float(version_val: Any) -> float:
    """Converts version strings like '1.0.3' to 1.03 or '2.1' to 2.1."""
    if isinstance(version_val, (int, float)):
        return float(version_val)
    
    if not isinstance(version_val, str):
        return 0.0

    # Remove letters (e.g. 'v1.0.3-SNAPSHOT' -> '1.0.3')
    clean = re.sub(r"[^\d.]", "", version_val)
    parts = [p for p in clean.split(".") if p]
    
    if not parts:
        return 0.0
    if len(parts) == 1:
        return float(parts[0])
    
    # Build from ['1', '0', '3'] -> '1.03'
    major = parts[0]
    minor = "".join(parts[1:])
    try:
        return float(f"{major}.{minor}")
    except ValueError:
        return 0.0


def _is_number(value: Any) -> bool:
    """Check if a value is a number (int or float, but not bool)."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _sanitize_metric_name(key: str) -> str:
    """Creates a valid Prometheus metric name from any JSON key."""
    name = "".join(c if c.isalnum() or c == "_" else "_" for c in key)
    if not name:
        name = "value"
    if name[0].isdigit():
        name = f"_{name}"
    return f"mc_{name}"


def _cleanup_locked() -> None:
    """Must be called within _lock. Removes stale entries."""
    now = time.time()
    stale = [sid for sid, data in _store.items() if now - data["ts"] > STALE_SECONDS]
    for sid in stale:
        del _store[sid]


async def _cleanup_loop() -> None:
    """Background task to keep memory clean even without /metrics calls."""
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL)
        async with _lock:
            _cleanup_locked()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manages the application lifespan, starting and stopping the cleanup task."""
    task = asyncio.create_task(_cleanup_loop())
    yield
    task.cancel()


app = FastAPI(title="MC Metrics Bridge", docs_url=None, redoc_url=None, lifespan=lifespan)


# --------------------------------------------------------------------------
# POST /push  – Receive metrics from Minecraft plugins
# --------------------------------------------------------------------------
@app.post("/push")
async def push(request: Request, authorization: Optional[str] = Header(default=None)):
    """Receive metrics from Minecraft plugins via POST request.
    
    Args:
        request: The HTTP request containing JSON metrics payload
        authorization: Bearer token for authentication
        
    Returns:
        JSON response with status and metrics count
        
    Raises:
        HTTPException: For authentication failures, invalid JSON, or missing required fields
    """
    if not authorization:
        raise HTTPException(status_code=401, detail="Missing Authorization header")

    token = authorization[7:].strip() if authorization.startswith("Bearer ") else authorization.strip()
    if token != SECRET_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid token")

    try:
        payload = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="JSON body must be an object")

    server_id = payload.get("server_id")
    if not server_id or not isinstance(server_id, str):
        raise HTTPException(status_code=400, detail="'server_id' (string) is required")

    # Filter all numeric metrics
    metrics = {k: v for k, v in payload.items() if k not in RESERVED_KEYS and _is_number(v)}
    
    # Parse version explicitly to float
    if "version" in payload:
        metrics["version"] = parse_version_to_float(payload["version"])

    if not metrics:
        raise HTTPException(status_code=400, detail="No valid metrics found in payload")

    async with _lock:
        _store[server_id] = {
            "ts": time.time(),
            "metrics": metrics,
        }

    return {"status": "ok", "server_id": server_id, "metrics_received": len(metrics)}


# --------------------------------------------------------------------------
# GET /metrics – Prometheus text format
# --------------------------------------------------------------------------
@app.get("/metrics", response_class=PlainTextResponse)
async def metrics():
    """Return all collected metrics in Prometheus text format.
    
    Returns:
        Plain text response with Prometheus-formatted metrics
    """
    async with _lock:
        _cleanup_locked()
        snapshot = {sid: data["metrics"] for sid, data in _store.items()}

    grouped: Dict[str, list] = {}
    for server_id, metrics_dict in snapshot.items():
        for key, value in metrics_dict.items():
            if key in METRIC_DEFS:
                name, mtype, help_text = METRIC_DEFS[key]
            else:
                name = _sanitize_metric_name(key)
                mtype = "gauge"
                help_text = f"Custom metric '{key}' reported by Minecraft plugin"
            grouped.setdefault(name, []).append((server_id, value, mtype, help_text))

    lines = []
    for name in sorted(grouped.keys()):
        entries = grouped[name]
        _, _, mtype, help_text = entries[0]
        lines.append(f"# HELP {name} {help_text}")
        lines.append(f"# TYPE {name} {mtype}")
        for server_id, value, _, _ in sorted(entries, key=lambda e: e[0]):
            lines.append(f'{name}{{server="{server_id}"}} {value}')

    lines.append("# HELP mc_bridge_servers_reporting Number of servers currently reporting fresh data")
    lines.append("# TYPE mc_bridge_servers_reporting gauge")
    lines.append(f"mc_bridge_servers_reporting {len(snapshot)}")

    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# GET /health – for Docker healthcheck
# --------------------------------------------------------------------------
@app.get("/health")
async def health():
    """Health check endpoint for Docker and monitoring systems.
    
    Returns:
        JSON response with status and cached server count
    """
    async with _lock:
        count = len(_store)
    return {"status": "ok", "servers_cached": count}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="info")