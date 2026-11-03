package dev.abex.gov.core;
import java.util.Set;
public record RealmSettings(double createCost,double maxTax,int unpaidDaysKick,int termDays,int joinTenureDays,
 double electionWithdrawCap,double spawnFee,int truceDays,int enemyDelayHours,boolean enemyPvp,Set<Perm> allyPerms) {
 public RealmSettings {
  if(!Double.isFinite(createCost)||createCost<0||!Double.isFinite(maxTax)||maxTax<0||unpaidDaysKick<1||termDays<1||joinTenureDays<0
   ||!Double.isFinite(electionWithdrawCap)||electionWithdrawCap<0||!Double.isFinite(spawnFee)||spawnFee<0||truceDays<0||enemyDelayHours<0)
   throw new IllegalArgumentException("Invalid realm settings.");
  allyPerms=Set.copyOf(allyPerms);
 }
 public static RealmSettings defaults(){return new RealmSettings(1000,100,3,14,7,100,0,3,24,true,Set.of(Perm.INTERACT));}
}
