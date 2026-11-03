package dev.abex.gov.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Phase1PersistenceTest {
    @Test void newStateAndClaimOrderSurviveJson() throws Exception {
        Phase1Test f = new Phase1Test();
        f.setup();
        f.book.claim(f.owner, Phase1Test.at(1, 0));
        f.book.claim(f.owner, Phase1Test.at(2, 0));
        f.book.flag(f.owner, "Home", "public", true);
        f.book.tax(f.owner, "Home", 5, 100);
        f.bank.deposit(f.owner, "Home", 50, 10);
        f.book.ban(f.owner, "Home", f.visitor, 10);
        f.land.taxStrikes.put(f.member, 3);
        f.land.upkeepStrikes = 4;
        f.data.lastEconomyDay = "2026-11-03";
        JsonStore store = new JsonStore(Files.createTempDirectory("gov-phase1").resolve("data.json"));
        store.save(f.data);
        GovData loaded = store.load();
        LandBook book = new LandBook(loaded, Phase1Test.roles(), new ClaimSettings(100, 4));
        Land land = book.land("Home").orElseThrow();
        assertEquals(f.land.spawn, land.spawn);
        assertTrue(book.spawn(f.member, "Home").ok());
        assertFalse(book.spawn(f.visitor, "Home").ok());
        assertEquals(50, land.balance);
        assertEquals(5, land.tax);
        assertEquals(3, land.taxStrikes.get(f.member));
        assertEquals(4, land.upkeepStrikes);
        assertEquals(f.land.ledger, land.ledger);
        assertEquals("2026-11-03", loaded.lastEconomyDay);
        assertEquals(Phase1Test.at(2, 0), book.unclaimNewest(land).orElseThrow());
        assertTrue(book.landAt(Phase1Test.at(2, 0)).isEmpty());
    }

    @Test void missingEconomyDoesNothingAndWarnsCommands() {
        Phase1Test f = new Phase1Test();
        f.setup();
        f.land.balance = 5;
        f.land.tax = 10;
        Bank bank = new Bank(f.book, null);
        assertEquals("No economy plugin installed.", bank.deposit(f.owner, "Home", 1, 0).message());
        assertFalse(bank.withdraw(f.owner, "Home", 1, 0).ok());
        new DayProcessor(f.book, null, 10, 3, 3, (l, m) -> fail("No economy must skip the day")).runDay(1);
        assertEquals(5, f.land.balance);
        assertEquals(0, f.land.upkeepStrikes);
        assertTrue(f.land.taxStrikes.isEmpty());
    }

    @Test void configDefaultsFillOldLandsWithoutOverwritingChoices() {
        Phase1Test f = new Phase1Test();
        f.setup();
        f.land.flags.clear();
        f.land.flags.put("fire", false);
        Map<String, Boolean> defaults = Land.defaultFlags();
        defaults.put("fire", true);
        defaults.put("public", true);
        LandBook book = new LandBook(f.data, Phase1Test.roles(), new ClaimSettings(8, 4), defaults);
        assertTrue(book.can(Phase1Test.at(0, 0), "public"));
        assertFalse(book.can(Phase1Test.at(0, 0), "fire"));
        assertTrue(book.create(f.visitor, "Newland", Phase1Test.at(5, 5), 0).ok());
        assertTrue(book.can(Phase1Test.at(5, 5), "fire"));
    }

    @Test void electedRolePowersAndRealElectionGuardApplyToBans() {
        Phase1Test f = new Phase1Test();
        f.setup();
        Settings settings = new Settings(0, 0, false, 48, 1, 30, 14, Set.of("Owner", "Member", "Count"));
        NativeLands port = new NativeLands(f.book, UUID::toString, (l, m) -> {});
        ElectionService gov = new ElectionService(f.data, port, settings);
        f.book.setGuard(gov.guard());
        gov.setVetted(f.member, true, "test");
        gov.observe("Home", f.member, 0);
        assertTrue(gov.startElection("Home", f.owner, "Count", 1, 1).ok());
        assertTrue(gov.run("Home", f.member, 1).ok());
        assertFalse(f.book.ban(f.owner, "Home", f.member, 1).ok());
        assertTrue(gov.vote("Home", f.member, f.member, 1).ok());
        gov.closeNow("Home", 2);
        assertTrue(f.book.setSpawn(f.member, "Home", Phase1Test.spot(2)).ok());
        assertTrue(f.book.flag(f.member, "Home", "public", true).ok());
        assertTrue(f.book.tax(f.member, "Home", 5, 100).ok());
        f.bank.deposit(f.owner, "Home", 10, 3);
        assertTrue(f.bank.withdraw(f.member, "Home", 5, 3).ok());
        assertTrue(f.book.ban(f.member, "Home", f.visitor, 3).ok());
    }

    @Test void warningLooksAheadAfterSuccessfulUpkeep() {
        Phase1Test f = new Phase1Test();
        f.setup();
        List<String> messages = new ArrayList<>();
        f.land.balance = 15;
        new DayProcessor(f.book, f.money, 10, 3, 3, (l, m) -> messages.add(m)).runDay(1);
        assertEquals(5, f.land.balance);
        assertEquals(0, f.land.upkeepStrikes);
        assertTrue(messages.stream().anyMatch(m -> m.contains("tomorrow")));
    }
}
