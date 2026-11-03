package dev.abex.gov.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static dev.abex.gov.core.Perm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Our own claims: the rules from DESIGN.md v0.2, then elections running on them through NativeLands. */
class LandBookTest {
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    static final String W = "world";

    final UUID owner = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID(),
            carol = UUID.randomUUID(), stranger = UUID.randomUUID();

    GovData data;
    LandBook book;
    long now = 1_000 * DAY;

    static ChunkPos at(int x, int z) {
        return new ChunkPos(W, x, z);
    }

    static RoleSet defaultRoles() {
        LinkedHashMap<String, Set<Perm>> r = new LinkedHashMap<>();
        r.put("Owner", EnumSet.allOf(Perm.class));
        r.put("Count", EnumSet.allOf(Perm.class));
        r.put("Admin", EnumSet.complementOf(EnumSet.of(SET_ROLES)));
        r.put("Citizen", EnumSet.of(BUILD, CONTAINERS, INTERACT, INVITE));
        r.put("Member", EnumSet.of(INTERACT));
        return RoleSet.of(r);
    }

    @BeforeEach
    void setUp() {
        data = new GovData();
        book = new LandBook(data, defaultRoles(), new ClaimSettings(8, 4));
        assertTrue(book.create(owner, "Abexilas", at(0, 0), now).ok());
        for (UUID p : new UUID[]{alice, bob, carol}) {
            assertTrue(book.invite(owner, "Abexilas", p, now).ok());
            assertTrue(book.join(p, "Abexilas", now).ok());
        }
    }

    @Test
    void createClaimsChunkAndMakesOwner() {
        Land l = book.land("Abexilas").orElseThrow();
        assertEquals(owner, l.owner);
        assertEquals("Owner", l.members.get(owner));
        assertEquals("Abexilas", book.landAt(at(0, 0)).orElseThrow().name);
        assertTrue(book.landAt(at(5, 5)).isEmpty());
    }

    @Test
    void namesAndChunksAreUnique() {
        assertFalse(book.create(stranger, "abexilas", at(9, 9), now).ok()); // name taken, any case
        assertFalse(book.create(stranger, "Other", at(0, 0), now).ok());    // chunk taken
        assertFalse(book.create(owner, "Second", at(20, 20), now).ok());    // already owns a land
    }

    @Test
    void joinedPlayersStartAsLowestRole() {
        assertEquals("Member", book.land("Abexilas").orElseThrow().members.get(alice));
    }

    @Test
    void joinNeedsAnInviteThatHasNotExpired() {
        assertFalse(book.join(stranger, "Abexilas", now).ok());
        book.invite(owner, "Abexilas", stranger, now);
        assertFalse(book.join(stranger, "Abexilas", now + 2 * DAY).ok());
    }

    @Test
    void claimMustTouchLandAndNeedsPermAndRespectsLimit() {
        assertTrue(book.claim(owner, at(1, 0)).ok());
        assertFalse(book.claim(owner, at(5, 5)).ok());   // not touching
        assertFalse(book.claim(alice, at(0, 1)).ok());   // Member lacks CLAIM
        // limit: 8 + 4 per member; 4 members -> 24 chunks
        for (int x = 2; x < 24; x++) assertTrue(book.claim(owner, at(x, 0)).ok(), "chunk " + x);
        assertFalse(book.claim(owner, at(24, 0)).ok());
    }

    @Test
    void unclaimNeedsPermAndKeepsLastChunk() {
        book.claim(owner, at(1, 0));
        assertFalse(book.unclaim(alice, at(1, 0)).ok());
        assertTrue(book.unclaim(owner, at(1, 0)).ok());
        assertTrue(book.landAt(at(1, 0)).isEmpty());
        assertFalse(book.unclaim(owner, at(0, 0)).ok()); // a land keeps at least one chunk; use /land delete
    }

    @Test
    void rolesGivePermsInsideTheLandOnly() {
        assertTrue(book.can(owner, at(0, 0), BUILD));
        assertFalse(book.can(alice, at(0, 0), BUILD));    // Member
        assertTrue(book.can(alice, at(0, 0), INTERACT));
        assertFalse(book.can(stranger, at(0, 0), INTERACT));
        assertTrue(book.can(stranger, at(50, 50), BUILD)); // wilderness
        assertTrue(book.setRole(owner, "Abexilas", alice, "Citizen").ok());
        assertTrue(book.can(alice, at(0, 0), BUILD));
    }

    @Test
    void setRoleNeedsToOutrankOldAndNewRole() {
        book.setRole(owner, "Abexilas", alice, "Admin");
        book.setRole(owner, "Abexilas", bob, "Admin");
        assertFalse(book.setRole(alice, "Abexilas", carol, "Citizen").ok()); // Admin lacks SET_ROLES
        book.setRole(owner, "Abexilas", alice, "Count");
        assertTrue(book.setRole(alice, "Abexilas", carol, "Citizen").ok());
        assertFalse(book.setRole(alice, "Abexilas", bob, "Count").ok());    // can't raise to own rank
        assertFalse(book.setRole(alice, "Abexilas", owner, "Member").ok()); // can't touch a higher rank
        assertFalse(book.setRole(owner, "Abexilas", bob, "Owner").ok());    // Owner only via /land owner
        assertFalse(book.setRole(owner, "Abexilas", bob, "Emperor").ok());  // no such role
    }

    @Test
    void kickNeedsPermAndRank() {
        assertFalse(book.kick(alice, "Abexilas", bob, now).ok());
        book.setRole(owner, "Abexilas", alice, "Admin");
        assertTrue(book.kick(alice, "Abexilas", bob, now).ok());
        assertFalse(book.land("Abexilas").orElseThrow().members.containsKey(bob));
        assertFalse(book.kick(alice, "Abexilas", owner, now).ok());
    }

    @Test
    void plotOwnerOnlyBuildsInTheirPlotNotEvenTheLandOwner() {
        book.claim(owner, at(1, 0));
        assertTrue(book.givePlot(owner, at(1, 0), alice).ok());
        assertTrue(book.can(alice, at(1, 0), BUILD));
        assertTrue(book.can(alice, at(1, 0), CONTAINERS));
        assertFalse(book.can(owner, at(1, 0), BUILD));
        assertFalse(book.can(owner, at(1, 0), CONTAINERS));
        assertTrue(book.can(owner, at(1, 0), INTERACT));
        assertFalse(book.givePlot(owner, at(9, 9), alice).ok());     // not our chunk
        assertFalse(book.givePlot(owner, at(1, 0), stranger).ok());  // not a member
        assertEquals(1, book.plotsOf("Abexilas", alice));
    }

    @Test
    void sameClaimSeparatesLandsPlotsAndWilderness() {
        book.claim(owner, at(1, 0));
        book.claim(owner, at(2, 0));
        book.givePlot(owner, at(2, 0), alice);
        assertTrue(book.sameClaim(at(0, 0), at(1, 0)));
        assertFalse(book.sameClaim(at(1, 0), at(2, 0)));  // land -> plot
        assertFalse(book.sameClaim(at(0, 0), at(0, -1))); // land -> wild
        assertTrue(book.sameClaim(at(40, 40), at(41, 40)));
    }

    @Test
    void leaveDeleteAndTransfer() {
        assertFalse(book.leave(owner, "Abexilas").ok()); // owner must hand over or delete
        assertTrue(book.leave(bob, "Abexilas").ok());
        assertFalse(book.transfer(alice, "Abexilas", carol, now).ok());
        assertTrue(book.transfer(owner, "Abexilas", carol, now).ok());
        Land l = book.land("Abexilas").orElseThrow();
        assertEquals(carol, l.owner);
        assertEquals("Owner", l.members.get(carol));
        assertNotEquals("Owner", l.members.get(owner));
        assertFalse(book.delete(owner, "Abexilas", now).ok());
        assertTrue(book.delete(carol, "Abexilas", now).ok());
        assertTrue(book.landAt(at(0, 0)).isEmpty());
        assertTrue(book.land("Abexilas").isEmpty());
    }

    @Test
    void landsOfListsMemberships() {
        assertEquals(List.of("Abexilas"), book.landsOf(alice));
        assertTrue(book.landsOf(stranger).isEmpty());
    }

    // --- governance on our own lands ---

    ElectionService governed() {
        Settings s = new Settings(7, 10, true, 48, 3, 30, 14, Set.of("Citizen", "Admin", "Count", "Owner", "Member"));
        List<String> said = new ArrayList<>();
        NativeLands port = new NativeLands(book, u -> u.toString().substring(0, 8), (land, msg) -> said.add(msg));
        ElectionService gov = new ElectionService(data, port, s);
        book.setGuard(gov.guard());
        for (UUID p : new UUID[]{owner, alice, bob, carol}) {
            gov.tryVet(p, 20 * 60, "ip-" + p);
            gov.observe("Abexilas", p, now - 10 * DAY);
        }
        return gov;
    }

    @Test
    void electedCountGetsRealPowers() {
        ElectionService gov = governed();
        assertFalse(book.claim(alice, at(1, 0)).ok());
        assertTrue(gov.startElection("Abexilas", owner, "Count", 0, now).ok());
        assertTrue(gov.run("Abexilas", alice, now).ok());
        gov.vote("Abexilas", bob, alice, now);
        gov.vote("Abexilas", carol, alice, now);
        gov.vote("Abexilas", owner, alice, now);
        gov.tick(now + 49 * HOUR);
        assertEquals("Count", book.land("Abexilas").orElseThrow().members.get(alice));
        assertTrue(book.claim(alice, at(1, 0)).ok()); // the role now means something
    }

    @Test
    void guardBlocksDeleteKickAndTransferDuringElection() {
        ElectionService gov = governed();
        gov.startElection("Abexilas", owner, "Count", 0, now);
        assertFalse(book.delete(owner, "Abexilas", now).ok());
        assertFalse(book.kick(owner, "Abexilas", alice, now).ok());
        assertFalse(book.transfer(owner, "Abexilas", alice, now).ok());
        assertTrue(book.land("Abexilas").isPresent());
    }

    @Test
    void signedCharterLocksPlots() {
        ElectionService gov = governed();
        book.claim(owner, at(1, 0));
        book.givePlot(owner, at(1, 0), alice);
        gov.signCharter("Abexilas", owner, Charter.Mode.COUNCIL, now);
        assertFalse(book.revokePlot(owner, at(1, 0)).ok()); // owner can't take it back
        assertTrue(book.revokePlot(alice, at(1, 0)).ok());  // plot owner may give it up
    }

    @Test
    void landsSurviveSaveAndLoad() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempDirectory("gov").resolve("data.json");
        book.claim(owner, at(1, 0));
        book.givePlot(owner, at(1, 0), alice);
        new JsonStore(f).save(data);
        GovData back = new JsonStore(f).load();
        LandBook again = new LandBook(back, defaultRoles(), new ClaimSettings(8, 4));
        assertEquals("Abexilas", again.landAt(at(1, 0)).orElseThrow().name);
        assertTrue(again.can(alice, at(1, 0), BUILD));
        assertFalse(again.can(owner, at(1, 0), BUILD));
    }
}
