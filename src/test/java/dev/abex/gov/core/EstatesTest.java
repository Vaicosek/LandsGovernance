package dev.abex.gov.core;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EstatesTest {
 UUID mayor=UUID.randomUUID(), owner=UUID.randomUUID(), guest=UUID.randomUUID();
 GovData data; LandBook book; Land city; Estate estate; Phase1Test.FakeEconomy money;
 Spot spot(int x) { return new Spot("world",x*16+1,70,1,0,0); }
 @BeforeEach void setup() {
  data=new GovData(); book=new LandBook(data,Phase1Test.roles(),new ClaimSettings(3,0));
  book.create(mayor,"City",spot(0),0); city=book.land("City").orElseThrow();
  book.invite(mayor,"City",owner,0); book.join(owner,"City",0);
  money=new Phase1Test.FakeEconomy(); book.setEconomy(money);
  assertTrue(book.createEstate(mayor,"Shop",owner,spot(0),0).ok()); estate=book.estate("Shop").orElseThrow();
 }
 @Test void uniqueNamesAndNoExtraSlot() {
  assertFalse(book.create(guest,"sHoP",spot(8),0).ok());
  assertFalse(book.createEstate(mayor,"CITY",owner,spot(0),0).ok());
  assertEquals(1,book.list().size()); assertTrue(book.create(owner,"Home",spot(8),0).ok());
  assertFalse(book.createEstate(mayor,"Outside",owner,spot(9),0).ok());
 }
 @Test void sharedLimitAndChunkSafety() {
  assertTrue(book.claim(mayor,spot(1).chunk()).ok());
  assertTrue(book.claimEstate(owner,"Shop",spot(1).chunk()).ok());
  assertTrue(book.claim(mayor,spot(2).chunk()).ok());
  assertFalse(book.claim(mayor,spot(3).chunk()).ok());
  assertFalse(book.claimEstate(owner,"Shop",spot(3).chunk()).ok());
  assertFalse(book.unclaim(mayor,spot(1).chunk()).ok());
  assertFalse(book.revokePlot(mayor,spot(1).chunk()).ok());
  assertFalse(book.unclaimEstate(owner,"Shop",spot(0).chunk()).ok());
 }
 @Test void protectionAndPublicEstate() {
  assertFalse(book.can(mayor,spot(0).chunk(),Perm.BUILD));
  assertTrue(book.can(owner,spot(0).chunk(),Perm.CONTAINERS));
  assertTrue(book.trustEstate(owner,"Shop",guest,true).ok());
  assertTrue(book.can(guest,spot(0).chunk(),Perm.BUILD));
  assertFalse(book.spawn(guest,"Shop").ok());
  assertTrue(book.publicEstate(owner,"Shop",true).ok());
  assertTrue(book.spawn(guest,"Shop").ok()); assertFalse(book.spawn(guest,"City").ok());
 }
 @Test void arrivalFeesAndCancellation() {
  book.publicEstate(owner,"Shop",true); book.fee(owner,"Shop",10,100);
  money.accounts.put(guest,20.0);
  assertTrue(book.arrive(guest,"Shop",1,()->true).ok());
  assertEquals(10,money.balance(guest)); assertEquals(10,estate.balance); assertEquals(1,estate.ledger.size());
  assertTrue(book.arrive(owner,"Shop",2,()->true).ok()); assertEquals(10,estate.balance);
  assertFalse(book.arrive(guest,"Shop",3,()->false).ok()); assertEquals(10,money.balance(guest));
  money.accounts.put(guest,0.0); assertFalse(book.arrive(guest,"Shop",4,()->{fail("Broke visitor teleported");return true;}).ok());
  assertEquals(10,estate.balance);
 }
 @Test void leavingPaysOwnerAndReturnsPlainChunks() {
  estate.balance=25; assertTrue(book.leave(owner,"City").ok());
  assertEquals(25,money.balance(owner)); assertTrue(city.estates.isEmpty());
  assertTrue(city.plots.isEmpty()); assertEquals(1,city.chunks.size());
 }
 @Test void charterProtectsEstate() {
  book.claim(mayor,spot(1).chunk()); book.claimEstate(owner,"Shop",spot(1).chunk());
  book.setGuard(new Guard(){public boolean blockKick(String l,UUID u,long n){return true;} public boolean blockDelete(String l){return false;} public boolean blockTransfer(String l){return false;} public boolean plotsLocked(String l){return true;}});
  assertFalse(book.deleteEstate(mayor,"Shop").ok());
  assertFalse(book.unclaimEstate(mayor,"Shop",spot(1).chunk()).ok());
  assertFalse(book.kick(mayor,"City",owner,0).ok());
  assertTrue(book.unclaimEstate(owner,"Shop",spot(1).chunk()).ok());
  assertFalse(book.unclaimEstate(owner,"Shop",spot(0).chunk()).ok());
  assertTrue(book.deleteEstate(owner,"Shop").ok());
 }

 @Test void treasuryTransferAndValidation() {
  Bank bank=new Bank(book,money); money.accounts.put(guest,100.0);
  assertTrue(bank.estateDeposit(guest,"Shop",20,1).ok()); assertEquals(20,estate.balance);
  assertFalse(bank.estateWithdraw(mayor,"Shop",1,2).ok());
  assertFalse(bank.estateDeposit(guest,"Shop",Double.NaN,2).ok());
  assertTrue(bank.estateWithdraw(owner,"Shop",5,2).ok()); assertEquals(5,money.balance(owner));
  assertFalse(book.giveEstate(mayor,"Shop",mayor).ok());
  assertFalse(book.giveEstate(owner,"Shop",guest).ok());
  assertTrue(book.giveEstate(owner,"Shop",mayor).ok());
  assertEquals(mayor,city.plots.get(spot(0).chunk().key())); assertEquals(15,estate.balance);
  assertFalse(book.can(owner,spot(0).chunk(),Perm.BUILD));
  assertFalse(book.fee(mayor,"Shop",Double.POSITIVE_INFINITY,100).ok());
  assertFalse(book.fee(mayor,"Shop",101,100).ok());
 }
 @Test void cityFeesAndNoEconomy() {
  book.flag(mayor,"City","public",true); assertTrue(book.fee(mayor,"City",5,100).ok());
  money.accounts.put(guest,10.0); assertTrue(book.arrive(guest,"City",1,()->true).ok());
  assertEquals(5,city.balance); assertEquals(5,money.balance(guest));
  book.setEconomy(null); assertTrue(book.arrive(guest,"City",2,()->true).ok());
  assertEquals(5,city.balance); assertEquals(5,money.balance(guest));
 }
 @Test void kickBanAndDeletionPayExactlyOnce() {
  estate.balance=12; assertTrue(book.kick(mayor,"City",owner,0).ok());
  assertEquals(12,money.balance(owner)); assertTrue(city.estates.isEmpty());
  book.invite(mayor,"City",owner,0); book.join(owner,"City",0);
  book.createEstate(mayor,"Shop",owner,spot(0),0); book.estate("Shop").orElseThrow().balance=8;
  assertTrue(book.ban(mayor,"City",owner,0).ok()); assertEquals(20,money.balance(owner));
  book.createEstate(mayor,"Store",mayor,spot(0),0); book.estate("Store").orElseThrow().balance=7;
  assertTrue(book.adminDelete("City").ok()); assertEquals(7,money.balance(mayor));
  assertFalse(book.adminDelete("City").ok()); assertEquals(7,money.balance(mayor));
 }
 @Test void failedPayoutRetainsPropertyAndMember() {
  estate.balance=10;
  book.setEconomy(new EconomyPort(){public double balance(UUID u){return 0;}public boolean withdraw(UUID u,double a){return false;}public void deposit(UUID u,double a){throw new IllegalStateException();}});
  assertFalse(book.leave(owner,"City").ok()); assertTrue(city.members.containsKey(owner));
  assertFalse(book.ban(mayor,"City",owner,0).ok()); assertFalse(city.banned.contains(owner));
  assertFalse(book.adminDelete("City").ok()); assertEquals(10,estate.balance);
  assertSame(estate,book.estate("Shop").orElseThrow());
 }
 @Test void normalDeletionPaysAndMapShowsEstate() {
  estate.balance=9; assertEquals("Shop (City)",ClaimMap.render(book,spot(1).chunk(),owner,1).get(1).get(1).land());
  assertEquals(ClaimMap.Kind.OWN_PLOT,ClaimMap.render(book,spot(1).chunk(),owner,1).get(1).get(1).kind());
  assertTrue(book.delete(mayor,"City",0).ok()); assertEquals(9,money.balance(owner));
 }
 @Test void namesStayUniqueAcrossCitiesAndEstates() {
  assertTrue(book.create(guest,"Other",spot(8),0).ok());
  assertFalse(book.createEstate(guest,"sHoP",guest,spot(8),0).ok());
  assertFalse(book.createEstate(guest,"cItY",guest,spot(8),0).ok());
  assertTrue(book.deleteEstate(owner,"Shop").ok());
  assertTrue(book.createEstate(guest,"SHOP",guest,spot(8),0).ok());
 }
 @Test void persistenceAndOldDataDefaults(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
  book.publicEstate(owner,"Shop",true); book.trustEstate(owner,"Shop",guest,true); book.fee(owner,"Shop",4,100);
  Bank.record(estate,1,guest,12,"Deposit");
  JsonStore store=new JsonStore(temp.resolve("data.json")); store.save(data);
  LandBook restored=new LandBook(store.load(),Phase1Test.roles(),new ClaimSettings(3,0));
  Estate saved=restored.estate("sHoP").orElseThrow();
  assertEquals(12,saved.balance); assertEquals(4,saved.spawnFee); assertEquals(spot(0),saved.spawn);
  assertEquals(1,saved.ledger.size()); assertTrue(saved.trusted.contains(guest));
  assertTrue(restored.spawn(guest,"Shop").ok()); assertEquals(owner,restored.land("City").orElseThrow().plots.get(spot(0).chunk().key()));
  java.nio.file.Files.writeString(temp.resolve("old.json"),"{\"lands\":{\"old\":{\"name\":\"Old\"}}}");
  Land old=new JsonStore(temp.resolve("old.json")).load().lands.get("old");
  assertNotNull(old.estates); assertTrue(old.estates.isEmpty()); assertEquals(0,old.spawnFee);
 }

 @Test void estatePermissionsSpawnMovementAndUpkeepSafety() {
  book.claim(mayor,spot(1).chunk());
  assertFalse(book.createEstate(owner,"Denied",owner,spot(1),0).ok());
  assertFalse(book.createEstate(mayor,"Outsider",guest,spot(1),0).ok());
  assertFalse(book.setEstateSpawn(mayor,"Shop",spot(0)).ok());
  assertFalse(book.setEstateSpawn(owner,"Shop",spot(1)).ok());
  assertTrue(book.claimEstate(owner,"Shop",spot(1).chunk()).ok());
  assertTrue(book.setEstateSpawn(owner,"Shop",spot(1)).ok());
  assertTrue(book.unclaimNewest(city).isEmpty());
  assertFalse(book.trustEstate(mayor,"Shop",guest,true).ok());
  book.trustEstate(owner,"Shop",guest,true); book.trustEstate(owner,"Shop",guest,false);
  assertFalse(book.can(guest,spot(0).chunk(),Perm.CONTAINERS));
  assertTrue(book.unclaimEstate(owner,"Shop",spot(0).chunk()).ok());
  assertTrue(city.chunks.contains(spot(0).chunk().key()));
  assertFalse(city.plots.containsKey(spot(0).chunk().key()));
 }
}
