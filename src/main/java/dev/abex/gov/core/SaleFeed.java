package dev.abex.gov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.function.Consumer;

/** Wire format and durable outbox. Access the queue only on the owning server thread. */
public final class SaleFeed {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public static final int CAPACITY = 5_000;
    public record Pending(String eventId, String body, int failures, long nextAttempt) {}
    private final GovData data;
    private final Consumer<String> warning;

    public SaleFeed(GovData data, Consumer<String> warning) {
        this.data = data; this.warning = warning;
    }

    public static String body(Sale sale, String server, String ownerName, String playerName) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event_id", server + ":" + sale.id());
        fields.put("time_ms", sale.time());
        fields.put("server", server);
        fields.put("shop", sale.shopKey());
        fields.put("owner_uuid", sale.owner().toString());
        fields.put("owner_name", ownerName);
        fields.put("city", sale.city());
        fields.put("player_uuid", sale.player().toString());
        fields.put("player_name", playerName);
        fields.put("side", sale.side().name());
        fields.put("item", sale.item());
        fields.put("qty", sale.qty());
        fields.put("price", sale.price());
        return JSON.toJson(fields);
    }

    public static String signature(String key, long time, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((time + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable", e); }
    }

    /** First failure waits 30 seconds, doubling to a ten-minute cap. */
    public static long backoff(int failures) {
        return Math.min(600_000L, 30_000L << Math.min(5, Math.max(0, failures - 1)));
    }

    public void enqueue(Sale sale, String server, String ownerName, String playerName) {
        data.saleFeed.add(new Pending(server + ":" + sale.id(), body(sale, server, ownerName, playerName), 0, 0));
        if (data.saleFeed.size() > CAPACITY) {
            data.saleFeed.subList(0, data.saleFeed.size() - CAPACITY).clear();
            warning.accept("Sale feed queue full; dropped oldest pending sale(s).");
        }
    }

    public Optional<Pending> due(long now) {
        return data.saleFeed.stream().filter(p -> p.nextAttempt() <= now).findFirst();
    }

    /** Status 0 represents a network error. A dropped in-flight entry must never be resurrected. */
    public void complete(Pending pending, int status, long now) {
        int index = data.saleFeed.indexOf(pending);
        if (index < 0) return;
        if ((status >= 200 && status < 300) || status == 409) data.saleFeed.remove(index);
        else {
            int failures = Math.min(31, pending.failures() + 1);
            data.saleFeed.set(index, new Pending(pending.eventId(), pending.body(), failures, now + backoff(failures)));
        }
    }
}
