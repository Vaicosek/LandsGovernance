package dev.abex.gov.core;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShopLogTest {
    @Test void localDayControlsDateAndCoordinatesStayShort() {
        ZoneId zone = ZoneId.of("Europe/Prague");
        long time = ZonedDateTime.of(2026, 11, 3, 14, 32, 0, 0, zone).toInstant().toEpochMilli();
        Sale sale = new Sale(1, time, "world:3:-59:10", UUID.randomUUID(), null,
                UUID.randomUUID(), Sale.Side.BUY, "diamond", 2, 10);
        assertEquals("14:32 · CityBot2 bought 2 diamond for 10.00 · Shop @ 3,-59,10",
                ShopLog.line(sale, "CityBot2", time, zone));
        assertEquals("03.11 14:32 · CityBot2 bought 2 diamond for 10.00 · Shop @ 3,-59,10",
                ShopLog.line(sale, "CityBot2", time + 86_400_000, zone));
        assertEquals("Shop @ 10,-59,4", ShopLog.location("world:10:-59:4"));
        long midnight = ZonedDateTime.of(2026, 11, 4, 0, 1, 0, 0, zone).toInstant().toEpochMilli();
        Sale sell = new Sale(2, midnight, sale.shopKey(), sale.owner(), "City", sale.player(), Sale.Side.SELL, "diamond", 2, 4);
        assertEquals("00:01 · CityBot2 sold 2 diamond for 4.00 · Shop @ 3,-59,10",
                ShopLog.line(sell, "CityBot2", midnight, zone));
    }
}
