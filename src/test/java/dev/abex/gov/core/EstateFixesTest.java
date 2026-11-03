package dev.abex.gov.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class EstateFixesTest {
    UUID mayor = UUID.randomUUID(), owner = UUID.randomUUID(), guest = UUID.randomUUID();
    LandBook book;
    Land city;
    Estate estate;
    Phase1Test.FakeEconomy money;
    static final long NOW = 123456789L;

    Spot spot(int x) { return new Spot("world", x * 16 + 1, 70, 1, 0, 0); }

    @BeforeEach void setup() {
        book = new LandBook(new GovData(), Phase1Test.roles(), new ClaimSettings(10, 0));
        assertTrue(book.create(mayor, "City", spot(0), 0).ok());
        city = book.land("City").orElseThrow();
        assertTrue(book.invite(mayor, "City", owner, 0).ok());
        assertTrue(book.join(owner, "City", 0).ok());
        assertTrue(book.createEstate(mayor, "Shop", owner, spot(0), 0).ok());
        estate = book.estate("Shop").orElseThrow();
        money = new Phase1Test.FakeEconomy();
        book.setEconomy(money);
    }

    void assertBanned(UUID player, ChunkPos chunk) {
        // Simulate persisted/stale ownership or trust: can() must defend independently of ban().
        city.banned.add(player);
        for (Perm permission : Perm.values())
            assertFalse(book.can(player, chunk, permission), permission.toString());
    }

    // Before the fix, the estate/lot early returns grant BUILD and CONTAINERS.
    @Test void banOverridesEstateTrust() {
        assertTrue(book.trustEstate(owner, "Shop", guest, true).ok());
        assertTrue(book.can(guest, spot(0).chunk(), Perm.BUILD));
        assertBanned(guest, spot(0).chunk());
    }

    @Test void banOverridesEstateOwnership() {
        assertTrue(book.can(owner, spot(0).chunk(), Perm.CONTAINERS));
        assertBanned(owner, spot(0).chunk());
    }

    @Test void banOverridesLotOwnership() {
        assertTrue(book.claim(mayor, spot(1).chunk()).ok());
        assertTrue(book.givePlot(mayor, spot(1).chunk(), owner).ok());
        assertTrue(book.can(owner, spot(1).chunk(), Perm.BUILD));
        assertBanned(owner, spot(1).chunk());
    }

    // Before the fix, ban() leaves both trust sets populated, even after unban().
    @Test void banningGuestClearsEveryEstateTrustListOnlyInThatCity() {
        assertTrue(book.claim(mayor, spot(1).chunk()).ok());
        assertTrue(book.createEstate(mayor, "Store", mayor, spot(1), 0).ok());
        assertTrue(book.create(owner, "Other", spot(8), 0).ok());
        assertTrue(book.createEstate(owner, "Elsewhere", owner, spot(8), 0).ok());
        for (String name : List.of("Shop", "Store", "Elsewhere"))
            assertTrue(book.trustEstate(book.estate(name).orElseThrow().owner, name, guest, true).ok());
        assertTrue(book.ban(mayor, "City", guest, NOW).ok());
        assertFalse(estate.trusted.contains(guest));
        assertFalse(book.estate("Store").orElseThrow().trusted.contains(guest));
        assertTrue(book.estate("Elsewhere").orElseThrow().trusted.contains(guest));
        assertTrue(book.unban(mayor, "City", guest).ok());
        assertFalse(book.can(guest, spot(0).chunk(), Perm.BUILD));
    }

    @Test void banningMemberClearsTrustInRemainingEstates() {
        assertTrue(book.claim(mayor, spot(1).chunk()).ok());
        assertTrue(book.createEstate(mayor, "Store", mayor, spot(1), 0).ok());
        Estate remaining = book.estate("Store").orElseThrow();
        assertTrue(book.trustEstate(mayor, "Store", owner, true).ok());
        assertTrue(book.ban(mayor, "City", owner, NOW).ok());
        assertFalse(remaining.trusted.contains(owner));
    }

    void withoutEconomy() { estate.balance = 12; book.setEconomy(null); }
    void assertDissolved() {
        assertTrue(book.estate("Shop").isEmpty());
        assertFalse(city.plots.containsKey(spot(0).chunk().key()));
        assertEquals(0, estate.balance);
        assertEquals(0, money.balance(owner));
        assertEquals(-12, estate.ledger.getFirst().amount());
    }

    // Before the fix each operation returns false at the missing-provider check.
    @Test void estateDeletionWithoutEconomyDropsAndLogsBalance() {
        withoutEconomy();
        List<LogRecord> logs = new ArrayList<>();
        Logger logger = Logger.getLogger(LandBook.class.getName());
        Handler handler = new Handler() {
            public void publish(LogRecord record) { logs.add(record); }
            public void flush() {}
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            assertTrue(book.deleteEstate(owner, "Shop").ok());
            assertDissolved();
            assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("Shop")
                    && r.getMessage().contains("City") && r.getMessage().contains("12.0")));
            assertFalse(book.deleteEstate(owner, "Shop").ok());
            assertEquals(1, estate.ledger.size());
        } finally { logger.removeHandler(handler); }
    }

    @Test void leavingWithoutEconomyDissolvesEstate() {
        withoutEconomy();
        assertTrue(book.leave(owner, "City").ok());
        assertDissolved();
        assertFalse(city.members.containsKey(owner));
    }

    @Test void kickingWithoutEconomyDissolvesEstate() {
        withoutEconomy();
        assertTrue(book.kick(mayor, "City", owner, NOW).ok());
        assertDissolved();
        assertFalse(city.members.containsKey(owner));
        assertEquals(NOW, estate.ledger.getFirst().time());
    }

    @Test void banningWithoutEconomyDissolvesEstate() {
        withoutEconomy();
        assertTrue(book.ban(mayor, "City", owner, NOW).ok());
        assertDissolved();
        assertTrue(city.banned.contains(owner));
        assertEquals(NOW, estate.ledger.getFirst().time());
    }

    @Test void deletingCityWithoutEconomyDissolvesEstate() {
        withoutEconomy();
        assertTrue(book.delete(mayor, "City", NOW).ok());
        assertDissolved();
        assertTrue(book.landAt(spot(0).chunk()).isEmpty());
        assertEquals(NOW, estate.ledger.getFirst().time());
    }

    @Test void adminDeletingCityWithoutEconomyDissolvesEstate() {
        withoutEconomy();
        assertTrue(book.adminDelete("City").ok());
        assertDissolved();
        assertTrue(book.land("City").isEmpty());
    }

    void assertPayoutTime() {
        assertEquals(12, money.balance(owner));
        assertEquals(0, estate.balance);
        assertEquals(1, estate.ledger.size());
        assertEquals(new LedgerEntry(NOW, owner, -12, "Owner payout"), estate.ledger.getFirst());
    }

    // Before the fix these entries use wall-clock time instead of NOW.
    @Test void kickPayoutUsesSuppliedTime() {
        estate.balance = 12;
        assertTrue(book.kick(mayor, "City", owner, NOW).ok());
        assertPayoutTime();
    }

    @Test void banPayoutUsesSuppliedTime() {
        estate.balance = 12;
        assertTrue(book.ban(mayor, "City", owner, NOW).ok());
        assertPayoutTime();
    }

    @Test void cityDeletionPayoutUsesSuppliedTime() {
        estate.balance = 12;
        assertTrue(book.delete(mayor, "City", NOW).ok());
        assertPayoutTime();
    }
}
