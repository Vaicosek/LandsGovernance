package dev.abex.gov.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonStoreTest {
    @TempDir Path dir;

    @Test
    void roundTripsElectionsAndPlayers() throws Exception {
        GovData d = new GovData();
        UUID p = UUID.randomUUID();
        PlayerRecord r = new PlayerRecord();
        r.vetted = true; r.ipHash = "abc";
        d.players.put(p, r);
        Election e = new Election("1", "Land", "Count", 5, 10);
        e.candidates.add(p);
        e.votes.put(p, p);
        d.elections.put(e.id, e);
        d.charters.put("Land", new Charter(Charter.Mode.COUNCIL, 5));

        JsonStore store = new JsonStore(dir.resolve("data.json"));
        store.save(d);
        GovData back = store.load();

        assertTrue(back.players.get(p).vetted);
        assertEquals(p, back.elections.get("1").votes.get(p));
        assertEquals(Election.State.OPEN, back.elections.get("1").state);
        assertEquals(Charter.Mode.COUNCIL, back.charters.get("Land").mode);
    }

    @Test
    void missingFileGivesEmptyData() throws Exception {
        assertTrue(new JsonStore(dir.resolve("none.json")).load().elections.isEmpty());
    }
}
