package dev.abex.gov.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ElectionServiceTest {
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    static final String LAND = "Abexilas";

    final UUID owner = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID(),
            carol = UUID.randomUUID(), dave = UUID.randomUUID(), newbie = UUID.randomUUID();

    FakeLands lands;
    GovData data;
    ElectionService gov;
    long now = 1_000 * DAY;

    @BeforeEach
    void setUp() {
        lands = new FakeLands();
        data = new GovData();
        Settings s = new Settings(7, 10, true, 48, 3, 30, 14, Set.of("Citizen", "Admin", "Count", "Owner"));
        gov = new ElectionService(data, lands, s);
        lands.create(LAND, owner);
        for (UUID p : new UUID[]{alice, bob, carol, dave}) {
            lands.trust(LAND, p, "Citizen");
        }
        // everyone vetted (distinct IPs, enough playtime) and a citizen for 10 days
        long tenStart = now - 10 * DAY;
        for (UUID p : new UUID[]{owner, alice, bob, carol, dave}) {
            assertTrue(gov.tryVet(p, 20 * 60, "ip-" + p).ok());
            gov.observe(LAND, p, tenStart);
        }
    }

    @Test
    void vettedMemberRunsAndWinsRole() {
        String id = gov.startElection(LAND, owner, "Count", 0, now).value();
        assertTrue(gov.run(LAND, alice, now).ok());
        assertTrue(gov.run(LAND, bob, now).ok());
        assertTrue(gov.vote(LAND, carol, alice, now).ok());
        assertTrue(gov.vote(LAND, dave, alice, now).ok());
        assertTrue(gov.vote(LAND, bob, bob, now).ok());

        gov.tick(now + 49 * HOUR);

        Election e = data.elections.get(id);
        assertEquals(Election.State.CLOSED, e.state);
        assertEquals(alice, e.winner);
        assertEquals("Count", lands.roleOf(LAND, alice).orElseThrow());
        assertTrue(lands.messages.stream().anyMatch(m -> m.contains("won")));
    }

    @Test
    void previousOfficeHolderReturnsToOldRole() {
        gov.startElection(LAND, owner, "Count", 0, now);
        gov.run(LAND, alice, now);
        gov.vote(LAND, bob, alice, now); gov.vote(LAND, carol, alice, now); gov.vote(LAND, dave, alice, now);
        now += 49 * HOUR; gov.tick(now);

        gov.startElection(LAND, owner, "Count", 0, now);
        gov.run(LAND, bob, now);
        gov.vote(LAND, alice, bob, now); gov.vote(LAND, carol, bob, now); gov.vote(LAND, dave, bob, now);
        now += 49 * HOUR; gov.tick(now);

        assertEquals("Count", lands.roleOf(LAND, bob).orElseThrow());
        assertEquals("Citizen", lands.roleOf(LAND, alice).orElseThrow());
    }

    @Test
    void unvettedOrFreshMembersCannotVoteOrRun() {
        lands.trust(LAND, newbie, "Citizen");
        gov.startElection(LAND, owner, "Count", 0, now);
        gov.run(LAND, alice, now);

        Result notVetted = gov.vote(LAND, newbie, alice, now);
        assertFalse(notVetted.ok());
        assertTrue(notVetted.message().contains("vetted"));

        assertTrue(gov.tryVet(newbie, 20 * 60, "ip-newbie").ok());
        gov.observe(LAND, newbie, now - DAY); // role handed out yesterday
        Result fresh = gov.run(LAND, newbie, now);
        assertFalse(fresh.ok());
        assertTrue(fresh.message().contains("7 days"));
    }

    @Test
    void altsOnSameIpAreNotVetted() {
        UUID alt = UUID.randomUUID();
        Result r = gov.tryVet(alt, 20 * 60, "ip-" + alice);
        assertFalse(r.ok());
        assertTrue(r.message().contains("another vetted account"));
        assertFalse(gov.tryVet(UUID.randomUUID(), 60, "fresh-ip").ok()); // 1 h playtime < 10 h
    }

    @Test
    void losingVotingRoleResetsTenure() {
        lands.trust(LAND, alice, "Visitor");
        gov.observe(LAND, alice, now);
        lands.trust(LAND, alice, "Citizen");
        gov.observe(LAND, alice, now + HOUR);
        gov.startElection(LAND, owner, "Count", 0, now + 2 * HOUR);
        assertFalse(gov.run(LAND, alice, now + 2 * HOUR).ok());
    }

    @Test
    void leavingAndRejoiningRestartsTenure() {
        lands.lands.get(LAND).roles.remove(alice);
        gov.sweep(LAND, now, false);
        lands.trust(LAND, alice, "Citizen");
        gov.sweep(LAND, now + HOUR, false);
        assertEquals(0, gov.tenureDays(LAND, alice, now + HOUR));
    }

    @Test
    void firstSweepAfterInstallGrandfathersExistingMembers() {
        lands.trust(LAND, newbie, "Citizen");
        gov.sweep(LAND, now, true);
        assertEquals(7, gov.tenureDays(LAND, newbie, now));
        assertEquals(10, gov.tenureDays(LAND, alice, now)); // already tracked: untouched
    }

    @Test
    void noQuorumChangesNothing() {
        String id = gov.startElection(LAND, owner, "Count", 0, now).value();
        gov.run(LAND, alice, now);
        gov.vote(LAND, bob, alice, now);
        gov.tick(now + 49 * HOUR);
        assertEquals(Election.State.NO_QUORUM, data.elections.get(id).state);
        assertEquals("Citizen", lands.roleOf(LAND, alice).orElseThrow());
    }

    @Test
    void tieGoesToLongestTenure() {
        gov.observe(LAND, bob, now - 20 * DAY); // no effect: bob's tenure already started
        data.votingSince.put(GovData.key(LAND, bob), now - 30 * DAY);
        String id = gov.startElection(LAND, owner, "Count", 0, now).value();
        gov.run(LAND, alice, now); gov.run(LAND, bob, now);
        gov.vote(LAND, carol, alice, now); gov.vote(LAND, dave, bob, now); gov.vote(LAND, owner, alice, now);
        gov.vote(LAND, owner, bob, now); // changed vote: alice 1, bob 2... then
        gov.vote(LAND, alice, alice, now); // alice 2, bob 2
        gov.tick(now + 49 * HOUR);
        assertEquals(bob, data.elections.get(id).winner);
    }

    @Test
    void onlyOwnerStartsAndOnlyOneOpenPerRole() {
        assertFalse(gov.startElection(LAND, alice, "Count", 0, now).ok());
        assertTrue(gov.startElection(LAND, owner, "Count", 0, now).ok());
        assertFalse(gov.startElection(LAND, owner, "Count", 0, now).ok());
        assertFalse(gov.startElection(LAND, owner, "Owner", 0, now).ok()); // owner office only in a republic
    }

    // --- abuse cases ---

    @Test
    void ownerDeleteBlockedDuringElectionButStaffDeleteVoids() {
        String id = gov.startElection(LAND, owner, "Count", 0, now).value();
        assertTrue(gov.blockDelete(LAND, false));
        assertFalse(gov.blockDelete(LAND, true)); // staff / upkeep
        assertEquals(Election.State.VOIDED, data.elections.get(id).state);
        assertFalse(gov.blockDelete(LAND, false)); // nothing open now
    }

    @Test
    void cannotPurgeEligibleVotersOrSwapOwnerDuringElection() {
        gov.startElection(LAND, owner, "Count", 0, now);
        assertTrue(gov.blockUntrust(LAND, alice, now));
        assertTrue(gov.blockOwnerChange(LAND));
        lands.trust(LAND, newbie, "Citizen");
        assertFalse(gov.blockUntrust(LAND, newbie, now)); // not eligible, can be removed
    }

    @Test
    void noCharterOwnerMayCancelAndMembersAreTold() {
        String id = gov.startElection(LAND, owner, "Count", 0, now).value();
        assertTrue(gov.cancel(LAND, owner).ok());
        assertEquals(Election.State.CANCELLED, data.elections.get(id).state);
        assertTrue(lands.messages.stream().anyMatch(m -> m.contains("cancelled")));
    }

    @Test
    void councilCharterBlocksCancelAndReopensWhenTermEnds() {
        assertTrue(gov.signCharter(LAND, owner, Charter.Mode.COUNCIL, now).ok());
        gov.startElection(LAND, owner, "Count", 0, now);
        assertFalse(gov.cancel(LAND, owner).ok());
        gov.run(LAND, alice, now);
        gov.vote(LAND, bob, alice, now); gov.vote(LAND, carol, alice, now); gov.vote(LAND, dave, alice, now);
        now += 49 * HOUR; gov.tick(now);
        assertEquals("Count", lands.roleOf(LAND, alice).orElseThrow());

        now += 30 * DAY; gov.tick(now); // term over: a new election opens by itself
        assertTrue(data.openElection(LAND, "Count").isPresent());
    }

    @Test
    void charterRevokeTakesNoticePeriod() {
        gov.signCharter(LAND, owner, Charter.Mode.COUNCIL, now);
        assertTrue(gov.revokeCharter(LAND, owner, now).ok());
        gov.tick(now + 13 * DAY);
        assertEquals(Charter.Mode.COUNCIL, gov.charter(LAND).mode);
        gov.tick(now + 14 * DAY + 1);
        assertEquals(Charter.Mode.NONE, gov.charter(LAND).mode);
        assertTrue(lands.messages.stream().anyMatch(m -> m.contains("14 days")));
    }

    @Test
    void republicElectsTheOwner() {
        gov.signCharter(LAND, owner, Charter.Mode.REPUBLIC, now);
        assertTrue(gov.startElection(LAND, owner, ElectionService.OWNER_OFFICE, 0, now).ok());
        gov.run(LAND, alice, now);
        gov.vote(LAND, bob, alice, now); gov.vote(LAND, carol, alice, now); gov.vote(LAND, dave, alice, now);
        gov.tick(now + 49 * HOUR);
        assertEquals(alice, lands.owner(LAND));
    }

    @Test
    void nonOwnerCannotSignCharter() {
        assertFalse(gov.signCharter(LAND, alice, Charter.Mode.REPUBLIC, now).ok());
    }
}
