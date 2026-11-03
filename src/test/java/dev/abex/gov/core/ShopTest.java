package dev.abex.gov.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ShopTest {
 UUID mayor=UUID.randomUUID(), member=UUID.randomUUID(), seller=UUID.randomUUID(), buyer=UUID.randomUUID();
 GovData data; LandBook book; Land city; Phase1Test.FakeEconomy money; ShopService shops;
 Spot spot(int x) { return new Spot("world",x*16+1,70,1,0,0); }
 ChunkPos inCity=spot(0).chunk(), wild=spot(9).chunk();
 @BeforeEach void setup() {
  data=new GovData(); book=new LandBook(data,Phase1Test.roles(),new ClaimSettings(3,0));
  book.create(mayor,"City",spot(0),0); city=book.land("City").orElseThrow();
  book.invite(mayor,"City",member,0); book.join(member,"City",0);
  money=new Phase1Test.FakeEconomy(); book.setEconomy(money);
  shops=new ShopService(data,book,money);
  money.deposit(buyer,100); money.deposit(seller,100);
 }
 @Test void playerShopBuyAndSellMoveMoney() {
  assertTrue(shops.create(seller,"world:300:64:5",wild,"diamond",2,10,4,false,0).ok());
  assertTrue(shops.buy(buyer,"world:300:64:5",0).ok());
  assertEquals(90,money.balance(buyer)); assertEquals(110,money.balance(seller));
  assertTrue(shops.sell(buyer,"world:300:64:5",0).ok());
  assertEquals(94,money.balance(buyer)); assertEquals(106,money.balance(seller));
 }
 @Test void cityShopUsesTreasury() {
  assertFalse(shops.create(member,"world:1:64:1",inCity,"bread",1,5,2,true,0).ok(), "members without BANK can't open a city shop");
  assertTrue(shops.create(mayor,"world:1:64:1",inCity,"bread",1,5,2,true,0).ok());
  assertTrue(shops.buy(buyer,"world:1:64:1",0).ok());
  assertEquals(5,city.balance); assertEquals(95,money.balance(buyer));
  assertTrue(shops.sell(buyer,"world:1:64:1",0).ok());
  assertEquals(3,city.balance); assertEquals(97,money.balance(buyer));
  assertEquals("Shop sale",city.ledger.get(0).reason());
 }
 @Test void cityShopCannotOverdrawTreasury() {
  shops.create(mayor,"world:1:64:1",inCity,"bread",1,5,8,true,0);
  assertFalse(shops.sell(buyer,"world:1:64:1",0).ok());
  assertEquals(0,city.balance); assertEquals(100,money.balance(buyer));
 }
 @Test void ownerShopCannotPayWithoutFunds() {
  shops.create(seller,"world:300:64:5",wild,"diamond",1,10,150,false,0);
  assertFalse(shops.sell(buyer,"world:300:64:5",0).ok());
  assertEquals(100,money.balance(buyer)); assertEquals(100,money.balance(seller));
 }
 @Test void buyerWithoutMoneyIsRefused() {
  shops.create(seller,"world:300:64:5",wild,"diamond",1,500,-1,false,0);
  assertFalse(shops.buy(buyer,"world:300:64:5",0).ok());
  assertFalse(shops.sell(buyer,"world:300:64:5",0).ok(), "no sell price set");
  assertEquals(100,money.balance(buyer)); assertEquals(100,money.balance(seller));
 }
 @Test void failedPayoutRefundsBuyer() {
  shops.create(seller,"world:300:64:5",wild,"diamond",1,10,-1,false,0);
  EconomyPort broken=new EconomyPort(){
   public double balance(UUID w){return money.balance(w);} public boolean withdraw(UUID w,double a){return money.withdraw(w,a);}
   public void deposit(UUID w,double a){if(w.equals(seller))throw new IllegalStateException("down");money.deposit(w,a);}};
  ShopService s=new ShopService(data,book,broken);
  assertFalse(s.buy(buyer,"world:300:64:5",0).ok());
  assertEquals(100,money.balance(buyer)); assertEquals(100,money.balance(seller));
 }
 @Test void invalidShopsAreRefused() {
  assertFalse(shops.create(seller,"world:300:64:5",wild,"diamond",0,10,4,false,0).ok());
  assertFalse(shops.create(seller,"world:300:64:5",wild,"diamond",1,-1,-1,false,0).ok(), "needs a buy or sell price");
  assertFalse(shops.create(seller,"world:300:64:5",wild,"diamond",1,Double.NaN,4,false,0).ok());
  assertFalse(shops.create(seller,"world:1:64:1",inCity,"diamond",1,10,4,false,0).ok(), "outsiders can't open shops in a city");
  assertFalse(shops.create(seller,"world:300:64:5",wild,"diamond",1,10,4,true,0).ok(), "city shop needs a city");
  assertTrue(shops.create(seller,"world:300:64:5",wild,"diamond",1,10,4,false,0).ok());
  assertFalse(shops.create(buyer,"world:300:64:5",wild,"diamond",1,10,4,false,0).ok(), "already a shop");
 }
 @Test void ownerCannotTradeWithOwnShopAndOnlyOwnerAccesses() {
  shops.create(seller,"world:300:64:5",wild,"diamond",1,10,4,false,0);
  assertFalse(shops.buy(seller,"world:300:64:5",0).ok());
  assertTrue(shops.canAccess(seller,"world:300:64:5")); assertFalse(shops.canAccess(buyer,"world:300:64:5"));
  assertFalse(shops.remove(buyer,"world:300:64:5").ok());
  assertTrue(shops.remove(seller,"world:300:64:5").ok()); assertTrue(shops.at("world:300:64:5").isEmpty());
 }
 @Test void cityShopAccessFollowsBankAndFallsBackToCreator() {
  shops.create(mayor,"world:1:64:1",inCity,"bread",1,5,2,true,0);
  assertTrue(shops.canAccess(mayor,"world:1:64:1")); assertFalse(shops.canAccess(member,"world:1:64:1"));
  book.adminDelete("City",0);
  assertTrue(shops.canAccess(mayor,"world:1:64:1"), "creator keeps the chest when the city is gone");
  assertFalse(shops.buy(buyer,"world:1:64:1",0).ok(), "a shop of a deleted city stops trading");
 }
 @Test void bannedPlayerCannotTradeInCity() {
  shops.create(mayor,"world:1:64:1",inCity,"bread",1,5,2,true,0);
  assertTrue(book.ban(mayor,"City",buyer,0).ok());
  assertFalse(shops.buy(buyer,"world:1:64:1",0).ok());
 }
 @Test void shopsSurviveSaveAndLoad(@TempDir Path dir) throws Exception {
  shops.create(seller,"world:300:64:5",wild,"diamond",2,10,4,false,0);
  JsonStore store=new JsonStore(dir.resolve("d.json")); store.save(data);
  Shop s=store.load().shops.get("world:300:64:5");
  assertEquals(seller,s.owner); assertEquals("diamond",s.item); assertEquals(2,s.qty); assertEquals(10,s.buy); assertEquals(4,s.sell);
 }
}
