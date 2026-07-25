package de.roaring20s.metrics;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;


/**
 * ServerMetrics
 *
 * Periodically collects system/server metrics (TPS, MSPT, CPU Load, Players, Memory, Chunks, Uptime, Plugin Version)
 * and sends them as JSON via HTTP POST to a central collection server.
 */
public final class ServerMetricsPlugin extends JavaPlugin {

    private HttpClient httpClient;
    private OperatingSystemMXBean osBean;
    private int taskId = -1;
    private String pluginVersion = "unknown";

    // Counter for failed send attempts
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    // Configuration values (loaded on Enable/Reload)
    private String targetUrl;
    private String serverId;
    private String authToken;
    private long sendIntervalSeconds;
    private boolean debugLogging;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        loadPluginVersion();

        // Initialize MXBean for CPU load
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean sunBean) {
                this.osBean = sunBean;
            }
        } catch (Throwable ignored) {
            getLogger().warning("OperatingSystemMXBean for CPU load could not be loaded.");
        }

        // Force HTTP/1.1 for compatibility with simple HTTP servers
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(1, getConfig().getInt("connect_timeout_seconds", 3))))
                .build();

        long intervalTicks = sendIntervalSeconds * 20L;
        this.taskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::collectAndSendMetrics,
                intervalTicks,
                intervalTicks
        ).getTaskId();

        getCommand("mcmetrics").setExecutor(new ReloadCommand(this));

        getLogger().info("ServerMetrics v" + pluginVersion + " enabled. Target: " + targetUrl
                + " | server_id=" + serverId
                + " | interval=" + sendIntervalSeconds + "s");
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        getLogger().info("ServerMetrics disabled.");
    }

    public void loadConfigValues() {
        FileConfiguration cfg = getConfig();
        this.targetUrl = cfg.getString("target_url", "http://127.0.0.1:5000/push");
        this.serverId = cfg.getString("server_id", "srv1");
        this.authToken = cfg.getString("auth_token", "");
        this.sendIntervalSeconds = Math.max(1, cfg.getLong("send_interval_seconds", 10));
        this.debugLogging = cfg.getBoolean("debug_logging", false);
    }

    public void restartScheduler() {
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
        }

        long intervalTicks = sendIntervalSeconds * 20L;
        this.taskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::collectAndSendMetrics,
                intervalTicks,
                intervalTicks
        ).getTaskId();
    }

    private void loadPluginVersion() {
        try (InputStream is = getResource("plugin.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                this.pluginVersion = props.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            getLogger().warning("Could not read plugin version from plugin.properties: " + e.getMessage());
        }
    }

    private void collectAndSendMetrics() {
        try {
            String json = buildMetricsJson();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(Math.max(1, getConfig().getInt("request_timeout_seconds", 5))))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (authToken != null && !authToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + authToken);
            }

            HttpRequest request = requestBuilder.build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            handleFailure("Could not send metrics to " + targetUrl + ": " + throwable.getMessage());
                            return;
                        }

                        int status = response.statusCode();
                        if (status < 200 || status >= 300) {
                            handleFailure("Collection server responded with status " + status 
                                    + " (" + targetUrl + "): " + response.body());
                        } else {
                            // Success -> reset counter
                            int previousFailures = consecutiveFailures.getAndSet(0);
                            if (previousFailures >= 10) {
                                getLogger().info("Connection to collection server restored after " 
                                        + previousFailures + " failures.");
                            } else if (debugLogging) {
                                getLogger().info("Metrics sent successfully (Status " + status + ").");
                            }
                        }
                    });
        } catch (Exception e) {
            handleFailure("Error collecting/sending metrics: " + e.getMessage());
        }
    }

    /**
     * Handles failed send attempts.
     * Before the 10th attempt only a warning is logged, from the 10th attempt the plugin raises an alarm.
     */
    private void handleFailure(String errorMessage) {
        int failures = consecutiveFailures.incrementAndGet();

        if (failures >= 10) {
            getLogger().severe("[ALARM] Metrics transmission is failing permanently! (Failed attempt #" 
                    + failures + "): " + errorMessage);
        } else if (failures > 2) {
            getLogger().warning("Warning when sending metrics (Failed attempt #" 
                    + failures + "/10): " + errorMessage);
        }
    }

    private String buildMetricsJson() {
        double tps1m = getTps1m();
        double mspt = getMspt();
        double cpuLoad = getCpuLoad();
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        int chunksLoaded = getLoadedChunksCount();

        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long freeMemoryMb = runtime.freeMemory() / (1024 * 1024);
        long totalMemoryMb = runtime.totalMemory() / (1024 * 1024);

        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonString(sb, "server_id", serverId).append(',');
        appendJsonString(sb, "version", pluginVersion).append(',');
        appendJsonNumber(sb, "tps", roundTo2(tps1m)).append(',');
        appendJsonNumber(sb, "mspt", roundTo2(mspt)).append(',');
        appendJsonNumber(sb, "cpu_load", roundTo2(cpuLoad)).append(',');
        appendJsonNumber(sb, "online_players", onlinePlayers).append(',');
        appendJsonNumber(sb, "max_players", maxPlayers).append(',');
        appendJsonNumber(sb, "chunks_loaded", chunksLoaded).append(',');
        appendJsonNumber(sb, "used_memory_mb", usedMemoryMb).append(',');
        appendJsonNumber(sb, "free_memory_mb", freeMemoryMb).append(',');
        appendJsonNumber(sb, "total_memory_mb", totalMemoryMb).append(',');
        appendJsonNumber(sb, "uptime_seconds", uptimeSeconds);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Retrieves the current TPS (Ticks Per Second) 1-minute average.
     * @return TPS value capped at 20.0, or 20.0 if unavailable
     */
    private double getTps1m() {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0 && !Double.isNaN(tps[0]) && !Double.isInfinite(tps[0])) {
                return Math.min(20.0, tps[0]);
            }
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    /**
     * Retrieves the average MSPT (Milliseconds Per Tick).
     * @return Average tick duration in milliseconds, or 0.0 if unavailable
     */
    private double getMspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    /**
     * Retrieves the current JVM process CPU load percentage.
     * @return CPU load as percentage (0.0 - 100.0), or 0.0 if unavailable
     */
    private double getCpuLoad() {
        if (osBean != null) {
            double load = osBean.getProcessCpuLoad();
            if (load >= 0.0) {
                return load * 100.0;
            }
        }
        return 0.0;
    }

    /**
     * Counts the total number of loaded chunks across all worlds.
     * @return Total loaded chunks count
     */
    private int getLoadedChunksCount() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            count += world.getLoadedChunks().length;
        }
        return count;
    }

    /**
     * Rounds a double value to 2 decimal places.
     * @param value The value to round
     * @return Rounded value with 2 decimal places
     */
    private static double roundTo2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Appends a JSON string key-value pair to the StringBuilder.
     * @param sb The StringBuilder to append to
     * @param key The JSON key
     * @param value The string value
     * @return The modified StringBuilder
     */
    private static StringBuilder appendJsonString(StringBuilder sb, String key, String value) {
        sb.append('"').append(escapeJson(key)).append("\":\"").append(escapeJson(value)).append('"');
        return sb;
    }

    /**
     * Appends a JSON number key-value pair to the StringBuilder.
     * @param sb The StringBuilder to append to
     * @param key The JSON key
     * @param value The numeric value
     * @return The modified StringBuilder
     */
    private static StringBuilder appendJsonNumber(StringBuilder sb, String key, Number value) {
        sb.append('"').append(escapeJson(key)).append("\":").append(value);
        return sb;
    }

    /**
     * Escapes special characters in a string for JSON compatibility.
     * @param input The input string to escape
     * @return The escaped string, or empty string if input is null
     */
    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
