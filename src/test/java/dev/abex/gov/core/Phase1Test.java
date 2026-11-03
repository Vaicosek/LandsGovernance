package dev.abex.gov.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.abex.gov.core.Perm.*;
import static org.junit.jupiter.api.Assertions.*;

class Phase1Test {
    final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), visitor = UUID.randomUUID();
    GovData data;
    LandBook book;
    Land land;
    FakeEconomy money;
    Bank bank;
    static ChunkPos at(int x, int z) { return new ChunkPos("world", x, z); }
    static Spot spot(int x) { return new Spot("world", x, 70, 1, 90, 0); }

    static RoleSet roles() {
        LinkedHashMap<String, Set<Perm>> roles = new LinkedHashMap<>();
        roles.put("Owner", EnumSet.allOf(Perm.class));
        roles.put("Count", EnumSet.allOf(Perm.class));
        roles.put("Admin", EnumSet.complementOf(EnumSet.of(SET_ROLES, BANK)));
        roles.put("Citizen", EnumSet.of(BUILD, CONTAINERS, INTERACT, INVITE));
        roles.put("Member", EnumSet.of(INTERACT));
        return RoleSet.of(roles);
    }

    @BeforeEach void setup() {
        data = new GovData();
        book = new LandBook(data, roles(), new ClaimSettings(100, 4));
        assertTrue(book.create(owner, "Home", spot(1), 0).ok());
        land = book.land("Home").orElseThrow();
        book.invite(owner, "Home", member, 0);
        book.join(member, "Home", 0);
        money = new FakeEconomy();
        money.accounts.put(owner, 1000.0);
        money.accounts.put(member, 100.0);
        bank = new Bank(book, money);
    }

    @Test void spawnUsesSettingsAndPublicAccess() {
        assertEquals(spot(1), land.spawn);
        assertTrue(book.spawn(member, "Home").ok());
        assertFalse(book.spawn(visitor, "Home").ok());
        assertFalse(book.setSpawn(member, "Home", spot(2)).ok());
        assertFalse(book.setSpawn(owner, "Home", spot(80)).ok());
        assertTrue(book.flag(owner, "Home", "public", true).ok());
        assertTrue(book.spawn(visitor, "Home").ok());
        book.ban(owner, "Home", visitor, 0);
        assertFalse(book.spawn(visitor, "Home").ok());
    }

    @Test void flagsAreValidatedAndVisitorsCannotBypassPlots() {
        for (String flag : Land.defaultFlags().keySet()) {
            assertFalse(book.flag(member, "Home", flag, true).ok());
            assertTrue(book.flag(owner, "Home", flag, true).ok());
            assertTrue(book.can(at(0, 0), flag));
            assertTrue(book.flag(owner, "Home", flag, false).ok());
            assertFalse(book.can(at(0, 0), flag));
        }
        assertFalse(book.flag(owner, "Home", "unknown", true).ok());
        assertFalse(book.can(visitor, at(0, 0), INTERACT));
        book.flag(owner, "Home", "visitor-interact", true);
        book.flag(owner, "Home", "visitor-containers", true);
        assertTrue(book.can(visitor, at(0, 0), INTERACT));
        assertTrue(book.can(visitor, at(0, 0), CONTAINERS));
        assertFalse(book.can(visitor, at(0, 0), BUILD));
        assertFalse(book.can(member, at(0, 0), CONTAINERS));
        book.givePlot(owner, at(0, 0), member);
        assertFalse(book.can(visitor, at(0, 0), CONTAINERS));
        assertTrue(book.can(member, at(0, 0), CONTAINERS));
    }

    @Test void mapHasNorthUpAndEveryCellKind() {
        book.claim(owner, at(1, 0));
        book.claim(owner, at(0, -1));
        book.givePlot(owner, at(1, 0), owner);
        book.create(visitor, "Other", at(-1, 0), 0);
        var rows = ClaimMap.render(book, at(0, 0), owner, 4);
        assertEquals(9, rows.size());
        assertEquals(17, rows.getFirst().size());
        assertEquals(ClaimMap.Kind.YOU, rows.get(4).get(8).kind());
        assertEquals(ClaimMap.Kind.OWN_PLOT, rows.get(4).get(9).kind());
        assertEquals(ClaimMap.Kind.OWN_LAND, rows.get(3).get(8).kind());
        assertEquals(ClaimMap.Kind.OTHER_LAND, rows.get(4).get(7).kind());
        assertEquals(ClaimMap.Kind.WILD, rows.get(5).get(8).kind());
        assertTrue(rows.get(5).get(8).claimable());
        assertFalse(book.claimAt(member, at(0, 0), at(0, 1)).ok());
        assertFalse(book.claimAt(owner, at(20, 20), at(0, 1)).ok());
        assertFalse(book.claimAt(owner, new ChunkPos("nether", 0, 0), at(0, 1)).ok());
        assertTrue(book.claimAt(owner, at(0, 0), at(0, 1)).ok());
    }

    @Test void bankChecksMoneyPermissionsAndKeepsFiftyEntries() {
        assertFalse(bank.deposit(visitor, "Home", 10, 0).ok());
        for (double amount : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1001})
            assertFalse(bank.deposit(owner, "Home", amount, 0).ok());
        assertTrue(bank.deposit(member, "Home", 20, 1).ok());
        assertEquals(80, money.balance(member));
        assertEquals(20, land.balance);
        assertFalse(bank.withdraw(member, "Home", 1, 2).ok());
        assertFalse(bank.withdraw(owner, "Home", 21, 2).ok());
        assertTrue(bank.withdraw(owner, "Home", 10, 2).ok());
        assertEquals(10, land.balance);
        assertEquals(1010, money.balance(owner));
        money.fail = true;
        assertFalse(bank.deposit(owner, "Home", 10, 3).ok());
        assertEquals(10, land.balance);
        money.fail = false;
        for (int i = 0; i < 60; i++) bank.deposit(owner, "Home", 1, i + 10);
        assertEquals(50, land.ledger.size());
        assertEquals(69, land.ledger.getLast().time());
        assertEquals(10, bank.recent(land).size());
    }

    @Test void adminHasSettingsAndBanButCannotWithdraw() {
        assertTrue(book.setRole(owner, "Home", member, "Admin").ok());
        assertTrue(book.setSpawn(member, "Home", spot(2)).ok());
        assertTrue(book.ban(member, "Home", visitor, 0).ok());
        bank.deposit(owner, "Home", 10, 0);
        assertFalse(bank.withdraw(member, "Home", 1, 0).ok());
    }

    DayProcessor days(double upkeep) {
        return new DayProcessor(book, money, upkeep, 3, 3, (l, m) -> {});
    }

    @Test void upkeepDrainsBankThenRemovesNewestNonSpawnOnlyAfterGrace() {
        book.claim(owner, at(1, 0));
        book.claim(owner, at(2, 0));
        book.setSpawn(owner, "Home", spot(33));
        land.balance = 5;
        DayProcessor day = days(10);
        for (int i = 1; i <= 3; i++) day.runDay(i);
        assertEquals(0, land.balance);
        assertEquals(3, land.upkeepStrikes);
        assertEquals(3, land.chunks.size());
        day.runDay(4);
        assertTrue(book.landAt(at(1, 0)).isEmpty());
        assertTrue(book.landAt(at(2, 0)).isPresent());
        day.runDay(5);
        assertTrue(book.landAt(at(0, 0)).isEmpty());
        day.runDay(6);
        assertEquals(Set.of(at(2, 0).key()), land.chunks);
        land.balance = 10;
        day.runDay(7);
        assertEquals(0, land.upkeepStrikes);
    }

    @Test void taxesComeFirstAndStrikesNeverKick() {
        assertFalse(book.tax(member, "Home", 10, 100).ok());
        assertFalse(book.tax(owner, "Home", 101, 100).ok());
        assertFalse(book.tax(owner, "Home", Double.NaN, 100).ok());
        assertTrue(book.tax(owner, "Home", 10, 100).ok());
        DayProcessor day = days(10);
        day.runDay(1);
        assertEquals(0, land.upkeepStrikes);
        assertEquals(0, land.balance);
        assertEquals(90, money.balance(member));
        assertEquals(1000, money.balance(owner));
        money.accounts.put(member, 0.0);
        for (int i = 2; i <= 5; i++) day.runDay(i);
        assertEquals(4, land.taxStrikes.get(member));
        assertTrue(land.members.containsKey(member));
        money.accounts.put(member, 10.0);
        day.runDay(6);
        assertFalse(land.taxStrikes.containsKey(member));
        assertTrue(book.tax(owner, "Home", 0, 100).ok());
    }

    @Test void bansUseRankAndElectionGuardAndClearInvites() {
        assertFalse(book.ban(member, "Home", owner, 0).ok());
        book.setGuard(new Guard() {
            public boolean blockKick(String l, UUID u, long n) { return true; }
            public boolean blockDelete(String l) { return true; }
            public boolean blockTransfer(String l) { return true; }
            public boolean plotsLocked(String l) { return true; }
        });
        assertFalse(book.ban(owner, "Home", member, 0).ok());
        assertTrue(land.members.containsKey(member));
        assertFalse(land.banned.contains(member));
        book.setGuard(Guard.NONE);
        assertTrue(book.ban(owner, "Home", member, 0).ok());
        assertFalse(land.members.containsKey(member));
        assertFalse(book.invite(owner, "Home", member, 0).ok());
        assertFalse(book.join(member, "Home", 0).ok());
        assertTrue(book.unban(owner, "Home", member).ok());
        assertFalse(land.banned.contains(member));
    }

    @Test void banUsesItsOwnPermissionAndRemovesMemberProperty() {
        LinkedHashMap<String, Set<Perm>> roles = new LinkedHashMap<>();
        roles.put("Owner", EnumSet.allOf(Perm.class));
        roles.put("Marshal", EnumSet.of(BAN));
        roles.put("Member", EnumSet.of(INTERACT));
        book = new LandBook(data, RoleSet.of(roles), new ClaimSettings(100, 4));
        book.invite(owner, "Home", visitor, 0);
        book.join(visitor, "Home", 0);
        book.setRole(owner, "Home", member, "Marshal");
        book.givePlot(owner, at(0, 0), visitor);
        assertFalse(book.kick(member, "Home", visitor, 0).ok());
        assertTrue(book.ban(member, "Home", visitor, 0).ok());
        assertTrue(land.plots.isEmpty());
        assertFalse(land.members.containsKey(visitor));
        assertFalse(book.ban(member, "Home", member, 0).ok());
        assertFalse(book.ban(member, "Home", owner, 0).ok());
    }

    @Test void listSortsByMemberCountThenNameAndAdminVoidsElections() {
        book.create(visitor, "Alpha", at(9, 9), 0);
        assertEquals(List.of("Home", "Alpha"), book.list().stream().map(l -> l.name).toList());
        book.leave(member, "Home");
        assertEquals(List.of("Alpha", "Home"), book.list().stream().map(l -> l.name).toList());
        Election election = new Election("1", "Home", "Count", 0, 100);
        data.elections.put("1", election);
        assertTrue(book.adminDelete("Home").ok());
        assertEquals(Election.State.VOIDED, election.state);
        assertTrue(data.elections.containsKey("1"));
        assertTrue(book.landAt(at(0, 0)).isEmpty());
    }

    static final class FakeEconomy implements EconomyPort {
        final Map<UUID, Double> accounts = new HashMap<>();
        boolean fail;
        public double balance(UUID who) { return accounts.getOrDefault(who, 0.0); }
        public boolean withdraw(UUID who, double amount) {
            if (fail || balance(who) < amount) return false;
            accounts.put(who, balance(who) - amount);
            return true;
        }
        public void deposit(UUID who, double amount) { accounts.put(who, balance(who) + amount); }
    }
}
