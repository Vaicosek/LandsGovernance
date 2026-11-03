package dev.abex.gov.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class RealmsPersistenceTest {
 @Test void roundTrip(@TempDir Path dir)throws Exception{
  RealmsTest f=new RealmsTest();f.setup();f.joinBeta(0);f.realms.tax(f.a,f.realm,8);f.realm.balance=42;
  f.realms.role(f.a,f.realm,f.b,"Steward",0,false);f.elected(8*RealmsTest.DAY);f.realms.run(f.b,f.realm,8*RealmsTest.DAY);f.realms.vote(f.a,f.realm,f.b,8*RealmsTest.DAY);
  f.realms.taxDay(9*RealmsTest.DAY);f.realms.invite(f.a,f.realm,"Gamma",9*RealmsTest.DAY);
  f.realms.create(f.c,"Gamma","Other",0);Realm other=f.realms.realm("Other").orElseThrow();
  f.realms.relate(f.a,f.realm,other,"truce",9*RealmsTest.DAY);f.realms.acceptRelation(f.c,other,f.realm,9*RealmsTest.DAY);
  f.realms.publicSpawn(f.a,f.realm,true);f.realms.fee(f.a,f.realm,6);f.data.pendingRefunds.put(f.visitor,5.0);
  JsonStore store=new JsonStore(dir.resolve("data.json"));store.save(f.data);GovData d=store.load();Realm r=d.realms.get("union");
  assertEquals(f.realm.balance,r.balance);assertEquals(f.realm.ledger,r.ledger);assertEquals(f.realm.cities,r.cities);assertEquals(f.realm.roles,r.roles);assertEquals(f.realm.spawn,r.spawn);
  assertTrue(r.publicSpawn);assertEquals(6,r.spawnFee);assertEquals(5,d.pendingRefunds.get(f.visitor));
  assertEquals(1,d.realmRelations.size());RealmRelation relation=d.realmRelations.values().iterator().next();
  assertEquals("truce",relation.state);assertEquals(12*RealmsTest.DAY,relation.truceUntil);
  assertEquals(Charter.Mode.REPUBLIC,d.realmGovernance.charters.get("Union").mode);
  assertEquals(f.data.realmInvites,d.realmInvites);assertEquals(f.data.lastRealmDay,d.lastRealmDay);assertEquals(1,d.realmGovernance.openElections("Union").size());
  assertEquals(f.b,d.realmGovernance.openElections("Union").getFirst().votes.get(f.a));
  java.nio.file.Files.writeString(dir.resolve("old.json"),"{}");assertNotNull(new JsonStore(dir.resolve("old.json")).load().realms);
 }
}
