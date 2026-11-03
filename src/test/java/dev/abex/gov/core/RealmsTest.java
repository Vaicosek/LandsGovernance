package dev.abex.gov.core;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RealmsTest {
    static final long DAY = 86_400_000L;
    UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID(), visitor=UUID.randomUUID();
    GovData data; LandBook book; ElectionService cityGov; RealmService realms; Money money;
    Land alpha, beta, gamma; Realm realm;
    static class Money implements EconomyPort {
        Map<UUID,Double> accounts=new HashMap<>(); boolean failDeposit;
        public double balance(UUID u){return accounts.getOrDefault(u,0.0);}
        public boolean withdraw(UUID u,double n){if(balance(u)<n)return false;accounts.put(u,balance(u)-n);return true;}
        public void deposit(UUID u,double n){if(failDeposit)throw new IllegalStateException();accounts.put(u,balance(u)+n);}
    }
    @BeforeEach void setup(){
        data=new GovData(); book=new LandBook(data,Phase1Test.roles(),new ClaimSettings(100,4));
        book.create(a,"Alpha",new Spot("world",1,70,1,0,0),0);
        book.create(b,"Beta",new Spot("world",161,70,1,0,0),0);
        book.create(c,"Gamma",new Spot("world",321,70,1,0,0),0);
        alpha=book.land("Alpha").orElseThrow(); beta=book.land("Beta").orElseThrow(); gamma=book.land("Gamma").orElseThrow();
        alpha.balance=100; beta.balance=100; gamma.balance=100; money=new Money();book.setEconomy(money);
        cityGov=new ElectionService(data,new NativeLands(book,UUID::toString,(n,m)->{}),new Settings(0,0,false,1,1,14,2,Set.of("Owner","Member")));
        realms=new RealmService(data,book,cityGov,money,new RealmSettings(10,100,3,14,7,20,5,3,24,true,Set.of(Perm.INTERACT)),RealmService.defaultRoles(),UUID::toString,(n,m)->{},m->{});
        for(UUID u:List.of(a,b,c)){cityGov.setVetted(u,true,"test");}
        for(Land l:book.list())cityGov.sweep(l.name,0,false);
        assertTrue(realms.create(a,"Alpha","Union",0).ok()); realm=realms.realm("Union").orElseThrow();
    }
    void joinBeta(long now){assertTrue(realms.invite(a,realm,"Beta",now).ok());assertTrue(realms.accept(b,"Union","Beta",now).ok());}
    void elected(long now){assertTrue(realms.sign(a,realm,now,false).ok());}
    @Test void creationCostUniquenessAndNoEconomy(){
        assertEquals(90,alpha.balance);assertEquals(0,money.balance(a));
        assertFalse(realms.create(b,"Beta","uNiOn",0).ok());assertEquals(100,beta.balance);
        beta.balance=9;assertFalse(realms.create(b,"Beta","Other",0).ok());assertEquals(9,beta.balance);
        assertFalse(realms.create(visitor,"Beta","Other",0).ok());
        RealmService free=new RealmService(data,book,cityGov,null,RealmSettings.defaults(),RealmService.defaultRoles(),UUID::toString,(n,m)->{},m->{});
        assertTrue(free.create(b,"Beta","Other",0).ok());assertEquals(9,beta.balance);
    }
    @Test void invitationExpiresAndCapitalCannotLeave(){
        assertFalse(realms.accept(b,"Union","Beta",0).ok());
        realms.invite(a,realm,"Beta",0);assertTrue(realms.ofCity("Beta").isEmpty());
        assertFalse(realms.accept(b,"Union","Beta",DAY).ok());joinBeta(DAY);
        assertFalse(realms.leave(a,realm,"Alpha",DAY).ok());
        assertTrue(realms.role(a,realm,b,"Steward",DAY,false).ok());
        assertTrue(realms.leave(b,realm,"Beta",DAY).ok());assertFalse(realm.roles.containsKey(b));
    }
    @Test void capitalDeletionSuccessionAndLastPayout(){
        joinBeta(0);realm.balance=40;beta.members.put(visitor,"Member");
        assertTrue(book.delete(a,"Alpha",1).ok());assertEquals("Beta",realm.capital);assertEquals(b,realms.ruler(realm));
        money.failDeposit=true;assertFalse(book.adminDelete("Beta").ok());assertEquals(40,realm.balance);
        money.failDeposit=false;assertTrue(book.adminDelete("Beta").ok());assertTrue(realms.list().isEmpty());assertEquals(40,money.balance(b));
    }
    @Test void deleteRefundsToCapital(){realm.balance=22;assertTrue(realms.delete(a,realm,1,false).ok());assertEquals(112,alpha.balance);assertTrue(realms.ofCity("Alpha").isEmpty());}
    @Test void taxIsDailySkipsCapitalConservesAndKicks(){
        joinBeta(0);realms.tax(a,realm,10);double total=alpha.balance+beta.balance+realm.balance;
        DayProcessor days=new DayProcessor(book,money,0,3,3,(n,m)->{});days.setRealms(realms);
        days.runDay(DAY);days.runDay(DAY+1);assertEquals(90,beta.balance);assertEquals(10,realm.balance);assertEquals(90,alpha.balance);
        assertEquals(total,alpha.balance+beta.balance+realm.balance);
        beta.balance=0;for(int i=2;i<=4;i++)days.runDay(i*DAY);
        assertTrue(realms.ofCity("Beta").isEmpty());assertEquals(10,realm.balance);
    }
    @Test void rulerElectionAndAllAbuseBlocks(){
        joinBeta(0);elected(8*DAY);assertTrue(realms.run(b,realm,8*DAY).ok());assertTrue(realms.vote(a,realm,b,8*DAY).ok());
        assertFalse(realms.kick(a,realm,"Beta",8*DAY,false).ok());assertFalse(realms.capital(a,realm,"Beta",8*DAY,false).ok());
        assertFalse(realms.delete(a,realm,8*DAY,false).ok());assertFalse(realms.revoke(a,realm,8*DAY,false).ok());
        assertFalse(realms.role(a,realm,b,"Envoy",8*DAY,false).ok());realm.balance=100;
        assertTrue(realms.withdraw(a,realm,15,8*DAY,false).ok());assertFalse(realms.withdraw(a,realm,6,8*DAY,false).ok());
        assertTrue(realms.withdraw(a,realm,5,8*DAY,false).ok());
        realms.tick(8*DAY+3_600_000);assertEquals(b,realms.ruler(realm));assertEquals(a,alpha.owner);assertTrue(realms.has(realm,b,Perm.REALM_ROLES));assertFalse(realms.has(realm,a,Perm.REALM_ROLES));
    }
    @Test void joiningDuringElectionCannotVoteEvenAfterTenure(){
        elected(8*DAY);joinBeta(8*DAY+1);assertFalse(realms.run(b,realm,8*DAY+2).ok());
        assertTrue(realms.run(a,realm,8*DAY+2).ok());assertFalse(realms.vote(b,realm,a,8*DAY+3).ok());
        assertFalse(realms.run(a,realm,9*DAY).ok());
    }
    @Test void largestCitySucceedsAndRealmSpawnIsProtected(){
        joinBeta(0);realms.invite(a,realm,"Gamma",0);realms.accept(c,"Union","Gamma",0);
        gamma.members.put(visitor,"Member");
        assertTrue(book.delete(a,"Alpha",1).ok());assertEquals("Gamma",realm.capital);assertEquals(c,realms.ruler(realm));
        assertTrue(book.claim(c,new ChunkPos("world",21,0)).ok());
        Spot moved=new Spot("world",337,70,1,0,0);assertTrue(realms.setSpawn(c,realm,moved).ok());
        assertFalse(book.unclaim(c,moved.chunk()).ok());assertTrue(book.unclaimNewest(gamma).isEmpty());
    }
    @Test void tieUsesEarlierCandidateAndOneChangeableVoteAcrossCities(){
        joinBeta(0);beta.members.put(a,"Member");cityGov.observe("Beta",a,0);
        data.votingSince.put(GovData.key("Alpha",a),-100*DAY);
        elected(8*DAY);assertTrue(realms.run(b,realm,8*DAY).ok());assertTrue(realms.run(a,realm,8*DAY).ok());
        realms.vote(a,realm,a,8*DAY);realms.vote(a,realm,b,8*DAY);realms.vote(b,realm,a,8*DAY);
        Election e=realms.elections().data().openElections("Union").getFirst();assertEquals(2,e.votes.size());
        realms.tick(e.closes);assertEquals(b,realms.ruler(realm));
    }
    @Test void vettingRoleTenureAndCityJoinTenureAreRequired(){
        joinBeta(2*DAY);elected(8*DAY);
        assertFalse(realms.run(b,realm,8*DAY).ok());
        cityGov.setVetted(a,false,"test");assertFalse(realms.run(a,realm,8*DAY).ok());
        cityGov.setVetted(a,true,"test");data.votingSince.remove(GovData.key("Alpha",a));assertFalse(realms.run(a,realm,8*DAY).ok());
    }
    @Test void charterNoticeAndStaffBypassesAreLogged(){
        List<String> audit=new ArrayList<>();
        realms=new RealmService(data,book,cityGov,money,new RealmSettings(10,100,3,14,7,20,5,3,24,true,Set.of(Perm.INTERACT)),RealmService.defaultRoles(),UUID::toString,(n,m)->{},audit::add);
        joinBeta(0);elected(8*DAY);
        assertFalse(realms.sign(a,realm,8*DAY,false).ok());
        assertTrue(realms.role(a,realm,b,"Steward",8*DAY,true).ok());assertTrue(realms.capital(a,realm,"Beta",8*DAY,true).ok());
        realm.balance=30;assertTrue(realms.withdraw(a,realm,30,8*DAY,true).ok());assertEquals(3,audit.size());
        assertTrue(realms.run(b,realm,8*DAY).ok());realms.vote(a,realm,b,8*DAY);realms.tick(8*DAY+3_600_000);
        assertTrue(realms.revoke(b,realm,9*DAY,false).ok());assertEquals(Charter.Mode.REPUBLIC,realms.charter(realm).mode);
        realms.tick(11*DAY);assertEquals(Charter.Mode.NONE,realms.charter(realm).mode);assertEquals(b,realms.ruler(realm));
    }
    @Test void failedRefundIsPersistedAndRetriedWithoutCreatingMoney(){
        realms.publicSpawn(a,realm,true);realms.fee(a,realm,5);money.accounts.put(visitor,20.0);money.failDeposit=true;
        assertFalse(realms.arrive(visitor,realm,0,()->false).ok());assertEquals(15,money.balance(visitor));
        assertEquals(5,data.pendingRefunds.get(visitor));assertEquals(0,realm.balance);
        money.failDeposit=false;realms.tick(1);realms.tick(2);assertEquals(20,money.balance(visitor));assertTrue(data.pendingRefunds.isEmpty());
    }
    @Test void realmTaxFollowsMemberTaxAndUpkeep(){
        joinBeta(0);beta.balance=0;beta.members.put(visitor,"Member");beta.tax=10;money.accounts.put(visitor,20.0);
        realms.tax(a,realm,7);DayProcessor days=new DayProcessor(book,money,3,3,3,(n,m)->{});days.setRealms(realms);days.runDay(DAY);
        assertEquals(10,money.balance(visitor));assertEquals(0,beta.balance);assertEquals(7,realm.balance);
        assertEquals(List.of("Daily tax","Daily upkeep","REALM_TAX"),beta.ledger.stream().map(LedgerEntry::reason).toList());
    }
    @Test void depositsAndTreasuryPayoutConserveAccountAndInternalBalances(){
        money.accounts.put(a,50.0);assertFalse(realms.deposit(visitor,realm,1,0).ok());
        assertFalse(realms.deposit(a,realm,Double.NaN,0).ok());assertFalse(realms.deposit(a,realm,51,0).ok());
        assertTrue(realms.deposit(a,realm,20,1).ok());assertEquals(50,money.balance(a)+realm.balance);
        money.failDeposit=true;assertFalse(realms.withdraw(a,realm,10,2,false).ok());assertEquals(50,money.balance(a)+realm.balance);
        money.failDeposit=false;assertTrue(realms.withdraw(a,realm,10,3,false).ok());assertEquals(50,money.balance(a)+realm.balance);
    }
    @Test void taxReceiverOverflowNeverDebitsOrStrikesAndRoleChecksApply(){
        joinBeta(0);realms.tax(a,realm,100);realm.balance=Double.MAX_VALUE;beta.balance=Double.MAX_VALUE;
        // An amount large enough to overflow an internal destination is rejected before the debit.
        realm.tax=Double.MAX_VALUE;realms.taxDay(DAY);assertEquals(Double.MAX_VALUE,beta.balance);assertTrue(realm.unpaid.isEmpty());
        assertFalse(realms.role(b,realm,a,"Envoy",0,false).ok());assertFalse(realms.tax(b,realm,1).ok());
        assertFalse(realms.role(a,realm,b,"Ruler",0,false).ok());
        assertTrue(realms.role(a,realm,b,"Steward",0,false).ok());assertTrue(realms.tax(b,realm,1).ok());
    }
    @Test void allyTravelIsFreeAndDeletionClearsThePair(){
        Realm other=other();realms.relate(a,realm,other,"ally",0);realms.acceptRelation(b,other,realm,0);
        realms.fee(a,realm,9);assertTrue(realms.arrive(b,realm,1,()->true).ok());assertEquals(0,realm.balance);
        assertTrue(book.fee(a,"Alpha",9,100).ok());assertTrue(book.arrive(b,"Alpha",1,()->true).ok());assertEquals(90,alpha.balance);
        assertEquals(1,data.realmRelations.size());assertTrue(realms.delete(b,other,1,false).ok());assertTrue(data.realmRelations.isEmpty());
    }
    Realm other(){assertTrue(realms.create(b,"Beta","Other",0).ok());return realms.realm("Other").orElseThrow();}
    @Test void allyConsentAndPermissionsAndSpawn(){
        Realm other=other();assertTrue(realms.relate(a,realm,other,"ally",0).ok());assertEquals("neutral",realms.relation(realm,other,0));
        assertFalse(realms.acceptRelation(a,other,realm,0).ok());assertTrue(realms.acceptRelation(b,other,realm,0).ok());
        assertTrue(book.can(b,alpha.spawn.chunk(),Perm.INTERACT));assertFalse(book.can(b,alpha.spawn.chunk(),Perm.BUILD));
        assertTrue(realms.spawn(b,realm,0).ok());assertTrue(book.spawn(b,"Alpha").ok());
    }
    @Test void enemyDeclarationPreservesExistingRelationUntilActivation(){
        Realm other=other();realms.relate(a,realm,other,"ally",0);realms.acceptRelation(b,other,realm,0);
        assertTrue(realms.relate(a,realm,other,"enemy",1).ok());assertEquals("ally",realms.relation(realm,other,DAY));
        assertEquals("enemy",realms.relation(realm,other,DAY+1));assertFalse(realms.spawn(b,realm,DAY+1).ok());
    }
    @Test void truceLockSurvivesNeutralAndEnemyDelayChangesPvp(){
        Realm other=other();realms.relate(a,realm,other,"truce",0);realms.acceptRelation(b,other,realm,0);
        assertFalse(realms.pvp(a,b,alpha.spawn.chunk(),beta.spawn.chunk(),true,0));
        realms.relate(a,realm,other,"neutral",1);assertFalse(realms.relate(a,realm,other,"enemy",DAY).ok());
        assertTrue(realms.relate(a,realm,other,"enemy",3*DAY).ok());assertEquals("neutral",realms.relation(realm,other,3*DAY));
        assertEquals("enemy",realms.relation(realm,other,4*DAY));assertTrue(realms.pvp(a,b,alpha.spawn.chunk(),beta.spawn.chunk(),false,4*DAY));
        assertFalse(realms.spawn(b,realm,4*DAY).ok());
    }
    @Test void arrivalsChargeOnlyOnceAndRefundFailure(){
        assertTrue(realms.spawn(a,realm,0).ok());assertFalse(realms.spawn(visitor,realm,0).ok());
        realms.publicSpawn(a,realm,true);realms.fee(a,realm,5);money.accounts.put(visitor,20.0);
        assertFalse(realms.arrive(visitor,realm,0,()->false).ok());assertEquals(20,money.balance(visitor));assertEquals(0,realm.balance);
        assertTrue(realms.arrive(visitor,realm,1,()->true).ok());assertEquals(15,money.balance(visitor));assertEquals(5,realm.balance);
        assertTrue(realms.arrive(a,realm,2,()->true).ok());assertEquals(5,realm.balance);
        money.failDeposit=true;assertFalse(realms.withdraw(a,realm,5,3,false).ok());assertEquals(5,realm.balance);
    }
}
