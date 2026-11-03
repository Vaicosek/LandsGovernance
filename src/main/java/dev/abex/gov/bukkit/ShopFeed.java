package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.scheduler.BukkitTask;

/** Only HTTP and signing run asynchronously; Bukkit lookups and durable state stay on the main thread. */
final class ShopFeed {
    private final GovPlugin plugin;
    private final SaleFeed queue;
    private final String url, market, key, server;
    private final boolean enabled;
    private BukkitTask timer;
    private boolean busy;
    private volatile boolean stopped;

    ShopFeed(GovPlugin plugin, GovData data) {
        this.plugin = plugin;
        queue = new SaleFeed(data, plugin.getLogger()::warning);
        var config = plugin.getConfig();
        url = config.getString("feed.url", "").trim();
        market = config.getString("feed.market-id", "").trim();
        key = config.getString("feed.key", "");
        server = config.getString("feed.server-id", "cities").trim();
        enabled = !url.isEmpty() && !market.isEmpty() && !key.isBlank() && !server.isEmpty();
        if (enabled) timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pump, 20L, 20L);
    }

    void record(Sale sale) {
        if (enabled) queue.enqueue(sale, server, name(sale.owner()), name(sale.player()));
    }

    private String name(UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? id.toString() : name;
    }

    private void pump() {
        if (busy || stopped) return;
        var pending = queue.due(System.currentTimeMillis()).orElse(null);
        if (pending == null) return;
        busy = true;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int status = send(pending.body());
            if (stopped) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (stopped) return;
                    queue.complete(pending, status, System.currentTimeMillis());
                    busy = false;
                    plugin.save();
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // Shutdown leaves the persisted entry queued for restart.
            }
        });
    }

    String server() { return server; }

    boolean webEnabled() {
        return enabled && !plugin.getConfig().getString("feed.web_base", "").isBlank();
    }

    void web(String body, java.util.function.Consumer<Boolean> done) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int status;
            try { status = send(body, URI.create(url).resolve("webview").toString()); }
            catch (IllegalArgumentException ex) { status = 0; }
            boolean ok = status >= 200 && status < 300;
            if (stopped) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!stopped) done.accept(ok);
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
        });
    }

    private int send(String body) { return send(body, url); }

    private int send(String body, String destination) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            long time = System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder(URI.create(destination)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Cities-Market", market)
                    .header("X-Cities-Time", Long.toString(time))
                    .header("X-Cities-Signature", SaleFeed.signature(key, time, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8)).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            // Do not log exceptions: request configuration may contain credentials.
            return 0;
        }
    }

    void stop() { stopped = true; if (timer != null) timer.cancel(); }
}
