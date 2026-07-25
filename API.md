## API Documentation

### POST /push

Receives metrics from Minecraft plugins.

**Headers**:
- `Authorization: Bearer <token>` (required)

**Body** (JSON):
```json
{
  "server_id": "servername",
  "version": "1.0.7",
  "tps": 20.0,
  "mspt": 12.5,
  "cpu_load": 45.3,
  "online_players": 15,
  "max_players": 50,
  "chunks_loaded": 1250,
  "used_memory_mb": 512,
  "free_memory_mb": 256,
  "total_memory_mb": 768,
  "uptime_seconds": 3600
}
```

**Response**:
```json
{
  "status": "ok",
  "server_id": "servername",
  "metrics_received": 10
}
```

### GET /metrics

Returns metrics in Prometheus text format.

**Response**:
```
# HELP mc_tps Current TPS of the Minecraft server (1m average, capped at 20.0)
# TYPE mc_tps gauge
mc_tps{server="srv1"} 20.0
mc_tps{server="srv2"} 19.5

# HELP mc_online_players Online players count
# TYPE mc_online_players gauge
mc_online_players{server="srv1"} 15
mc_online_players{server="srv2"} 8

# HELP mc_bridge_servers_reporting Number of servers currently reporting fresh data
# TYPE mc_bridge_servers_reporting gauge
mc_bridge_servers_reporting 2
```

### GET /health

Health check endpoint for Docker.

**Response**:
```json
{
  "status": "ok",
  "servers_cached": 2
}
```

## Prometheus Configuration

Add the following to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'minecraft-metrics'
    scrape_interval: 15s
    static_configs:
      - targets: ['mc-metrics-bridge:5000']
```