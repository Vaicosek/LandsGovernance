package dev.abex.gov.core;

import java.time.Instant;
import java.util.*;
import java.util.function.*;
import static dev.abex.gov.core.Perm.*;

/** Server-thread realm rules. No Bukkit dependencies; all changes return Result. */
public final class RealmService {
 public static final long DAY=86_400_000L;
 private final GovData data;
 private final LandBook book;
 private final ElectionService cityGov;
 private final EconomyPort economy;
 private final RealmSettings settings;
 private final RoleSet roles;
 private final BiConsumer<String,String> announcer;
 private final Consumer<String> audit;
 private final ElectionService elections;

 public RealmService(GovData data,LandBook book,ElectionService cityGov,EconomyPort economy,RealmSettings settings,
                     RoleSet roles,Function<UUID,String> names,BiConsumer<String,String> announcer,Consumer<String> audit){
  this.data=data;this.book=book;this.cityGov=cityGov;this.economy=economy;this.settings=settings;this.roles=roles;this.announcer=announcer;this.audit=audit;
  if(data.realmGovernance==null)data.realmGovernance=new GovData();
  Settings s=cityGov.settings();
  LandsPort port=new LandsPort(){
   public boolean landExists(String n){return realm(n).isPresent();}
   public UUID owner(String n){return realm(n).map(RealmService.this::ruler).orElse(null);}
   public Set<UUID> members(String n){return realm(n).map(RealmService.this::members).orElse(Set.of());}
   public Optional<String> roleOf(String n,UUID u){return realm(n).map(r->r.roles.get(u));}
   public boolean hasRole(String n,String role){return roles.exists(role)!=null;}
   public void setRole(String n,UUID u,String role){realm(n).ifPresent(r->r.roles.put(u,role));}
   public void setOwner(String n,UUID u){realm(n).ifPresent(r->setRuler(r,u));}
   public String name(UUID u){return names.apply(u);}
   public void announce(String n,String message){realm(n).ifPresent(r->tell(r,message.replace("/c run","/c realm run").replace("/c vote","/c realm vote")
     .replace("Owner","Ruler").replace("owner","ruler").replace("republic","elected").replace("/c charter","/c realm charter")));}
  };
  elections=new ElectionService(data.realmGovernance,port,new Settings(s.tenureDays(),s.minPlaytimeHours(),s.oneVettedPerIp(),s.defaultElectionHours(),s.minVoters(),settings.termDays(),s.charterRevokeDays(),s.votingRoles())){
   @Override public Result eligibility(String name,UUID u,long now){return realm(name).map(r->eligible(r,u,now)).orElse(Result.no("No such realm."));}
   @Override protected long candidateSeniority(Election e,UUID candidate){return 0;}
  };
  book.setRealms(this);
 }
 public static RoleSet defaultRoles(){
  LinkedHashMap<String,Set<Perm>> r=new LinkedHashMap<>();
  Set<Perm> all=EnumSet.of(REALM_INVITE,REALM_KICK,REALM_TREASURY,REALM_TAX,REALM_RELATIONS,REALM_SPAWN,REALM_ROLES);
  r.put("Ruler",all);Set<Perm> ch=EnumSet.copyOf(all);ch.remove(REALM_ROLES);r.put("Chancellor",ch);
  r.put("Steward",Set.of(REALM_TREASURY,REALM_TAX));r.put("Envoy",Set.of(REALM_RELATIONS,REALM_INVITE));return RoleSet.of(r);
 }
 private static String key(String s){return s.toLowerCase(Locale.ROOT);}
 public Optional<Realm> realm(String name){return Optional.ofNullable(data.realms.get(key(name)));}
 public List<Realm> list(){return List.copyOf(data.realms.values());}
 public Optional<Realm> ofCity(String name){return list().stream().filter(r->r.cities.containsKey(key(name))).findFirst();}
 public Set<UUID> members(Realm r){Set<UUID> out=new LinkedHashSet<>();for(String c:r.cities.keySet())book.land(c).ifPresent(l->out.addAll(l.members.keySet()));return out;}
 public List<Realm> ofPlayer(UUID u){return list().stream().filter(r->members(r).contains(u)).toList();}
 public GovData data(){return data;}
 public RealmRelation relationRecord(Realm a,Realm b){return pair(a,b,false);}
 public RoleSet roles(){return roles;}
 public ElectionService elections(){return elections;}
 public Charter charter(Realm r){return elections.charter(r.name);}
 public UUID ruler(Realm r){
  if(charter(r).mode==Charter.Mode.NONE || !members(r).contains(r.ruler))book.land(r.capital).ifPresent(c->{if(!c.owner.equals(r.ruler))setRuler(r,c.owner);});
  return r.ruler;
 }
 private void setRuler(Realm r,UUID u){
  // Never leave a second holder of the top role after an election or succession.
  r.roles.entrySet().removeIf(e->roles.top().equals(e.getValue()));r.ruler=u;r.roles.put(u,roles.top());
 }
 public boolean has(Realm r,UUID u,Perm p){return r!=null&&members(r).contains(u)&&roles.has(u.equals(ruler(r))?roles.top():r.roles.get(u),p);}
 private boolean owner(Realm r,UUID u){return r!=null&&u.equals(ruler(r));}
 public void tell(Realm r,String m){for(String c:r.cities.keySet())book.land(c).ifPresent(l->announcer.accept(l.name,m));}
 private Result block(Realm r,long now,boolean staff,String action){
  Election e=data.realmGovernance.openElections(r.name).stream().findFirst().orElse(null);
  if(e==null)return Result.ok("Allowed.");
  if(staff){audit.accept("Staff bypass: realm "+r.name+" "+action+" at "+Instant.ofEpochMilli(now));return Result.ok("Staff bypass.");}
  return Result.no("blocked: realm election open until "+Instant.ofEpochMilli(e.closes));
 }
 public Result create(UUID actor,String city,String name,long now){
  Land c=book.land(city).orElse(null);
  if(c==null||!actor.equals(c.owner))return Result.no("Only a city owner can create a realm.");
  if(ofCity(city).isPresent())return Result.no("This city already belongs to a realm.");
  if(name==null||!name.matches("[A-Za-z0-9_]{3,24}"))return Result.no("Use 3-24 letters, numbers or underscores.");
  if(realm(name).isPresent())return Result.no("That realm name is taken.");
  double cost=economy==null?0:settings.createCost();
  if(c.balance<cost)return Result.no("The city treasury cannot cover realm creation.");
  Realm r=new Realm();r.name=name;r.capital=c.name;r.spawn=c.spawn;r.spawnFee=settings.spawnFee();r.cities.put(key(c.name),now);setRuler(r,actor);
  if(cost>0)Bank.record(c,now,actor,-cost,"REALM_CREATE");data.realms.put(key(name),r);
  return Result.ok("Created realm "+name+".");
 }
 public Result invite(UUID actor,Realm r,String city,long now){
  if(!has(r,actor,REALM_INVITE))return Result.no("You need REALM_INVITE.");
  if(book.land(city).isEmpty()||ofCity(city).isPresent())return Result.no("Choose a city without a realm.");
  data.realmInvites.put(key(r.name)+"|"+key(city),now+DAY);return Result.ok("City invited for one day.");
 }
 public Result accept(UUID actor,String name,String city,long now){
  Realm r=realm(name).orElse(null);Land c=book.land(city).orElse(null);String k=key(name)+"|"+key(city);
  if(r==null||c==null||!actor.equals(c.owner)||ofCity(city).isPresent()||data.realmInvites.getOrDefault(k,Long.MIN_VALUE)<=now)return Result.no("Only the invited city owner can accept a valid invitation.");
  r.cities.put(key(c.name),now);data.realmInvites.remove(k);tell(r,c.name+" joined "+r.name+".");return Result.ok("Joined "+r.name+".");
 }
 public Result decline(UUID actor,String name,String city){
  Land c=book.land(city).orElse(null);if(c==null||!actor.equals(c.owner))return Result.no("Only the city owner can decline.");
  data.realmInvites.remove(key(name)+"|"+key(city));return Result.ok("Invitation declined.");
 }
 private void detach(Realm r,Land c){
  announcer.accept(c.name,c.name+" left realm "+r.name+".");r.cities.remove(key(c.name));r.unpaid.remove(key(c.name));
  // Explicitly revoke every departing city's assigned roles, even for dual city members.
  c.members.keySet().forEach(r.roles::remove);pruneBallot(r);
  tell(r,c.name+" left realm "+r.name+".");
  if(!members(r).contains(r.ruler))book.land(r.capital).ifPresent(cap->setRuler(r,cap.owner));
 }
 public Result kick(UUID actor,Realm r,String city,long now,boolean staff){
  if(!has(r,actor,REALM_KICK)&&!staff)return Result.no("You need REALM_KICK.");
  Result b=block(r,now,staff,"kick by "+actor);if(!b.ok())return b;
  if(r.capital.equalsIgnoreCase(city))return Result.no("The capital cannot leave.");
  Land c=book.land(city).orElse(null);if(c==null||!r.cities.containsKey(key(city)))return Result.no("That city is not a member.");
  detach(r,c);return Result.ok("City removed.");
 }
 public Result leave(UUID actor,Realm r,String city,long now){
  Land c=book.land(city).orElse(null);if(c==null||!actor.equals(c.owner)||!r.cities.containsKey(key(city)))return Result.no("Only a member city owner can leave.");
  if(r.capital.equalsIgnoreCase(city))return Result.no("The capital cannot leave.");
  detach(r,c);return Result.ok("Left realm.");
 }
 public Result capital(UUID actor,Realm r,String city,long now,boolean staff){
  if(!owner(r,actor)&&!staff)return Result.no("Only the ruler can move the capital.");
  Result b=block(r,now,staff,"capital by "+actor);if(!b.ok())return b;
  Land c=book.land(city).orElse(null);if(c==null||!r.cities.containsKey(key(city)))return Result.no("Choose a member city.");
  r.capital=c.name;r.spawn=c.spawn;r.unpaid.remove(key(c.name));ruler(r);tell(r,c.name+" is now the capital.");return Result.ok("Capital moved.");
 }
 private void erase(Realm r){
  tell(r,"Realm "+r.name+" was deleted; its cities are free.");data.realms.remove(key(r.name));
  data.realmInvites.keySet().removeIf(k->k.startsWith(key(r.name)+"|"));
  data.realmRelations.values().removeIf(p->p.first.equals(key(r.name))||p.second.equals(key(r.name)));
  data.realmGovernance.elections.values().removeIf(e->e.land.equalsIgnoreCase(r.name));
  data.realmGovernance.charters.remove(r.name);data.realmGovernance.offices.keySet().removeIf(k->k.startsWith(r.name+"|"));
 }
 public Result delete(UUID actor,Realm r,long now,boolean staff){
  if(!owner(r,actor)&&!staff)return Result.no("Only the ruler can delete the realm.");
  Result b=block(r,now,staff,"delete by "+actor);if(!b.ok())return b;
  Land cap=book.land(r.capital).orElse(null);if(cap==null||!Double.isFinite(cap.balance+r.balance))return Result.no("Capital treasury cannot accept payout.");
  double amount=r.balance;if(amount>0){Bank.record(r,now,actor,-amount,"REALM_DELETE");Bank.record(cap,now,actor,amount,"REALM_DELETE");}
  erase(r);return Result.ok("Realm deleted; treasury paid to the capital.");
 }
 /** Called by the single city deletion path before removing its membership and owner data. */
 public Result deletingCity(Land c,long now){
  Realm r=ofCity(c.name).orElse(null);if(r==null){data.realmInvites.keySet().removeIf(k->k.endsWith("|"+key(c.name)));return Result.ok("No realm.");}
  if(r.cities.size()==1){
   if(r.balance>0){if(economy==null)return Result.no("Economy unavailable; realm payout retained.");
    try{economy.deposit(c.owner,r.balance);}catch(RuntimeException fail){return Result.no("Realm payout failed; city retained.");}
    Bank.record(r,now,c.owner,-r.balance,"REALM_LAST_CAPITAL");}
   data.realmInvites.keySet().removeIf(k->k.endsWith("|"+key(c.name)));
   erase(r);return Result.ok("Realm closed.");
  }
  if(r.capital.equalsIgnoreCase(c.name)){
   Land next=r.cities.keySet().stream().filter(n->!n.equals(key(c.name))).map(n->book.land(n).orElseThrow())
    .sorted(Comparator.<Land>comparingInt(l->-l.members.size()).thenComparing(l->l.name,String.CASE_INSENSITIVE_ORDER)).findFirst().orElseThrow();
   r.capital=next.name;r.spawn=next.spawn;tell(r,next.name+" succeeds as capital.");
  }
  data.realmInvites.keySet().removeIf(k->k.endsWith("|"+key(c.name)));
  detach(r,c);ruler(r);return Result.ok("Realm membership updated.");
 }
 public Result role(UUID actor,Realm r,UUID target,String role,long now,boolean staff){
  if(!has(r,actor,REALM_ROLES)&&!staff)return Result.no("You need REALM_ROLES.");
  Result b=block(r,now,staff,"role by "+actor);if(!b.ok())return b;
  String canonical=roles.exists(role);int rank=roles.rank(r.roles.get(actor)),old=roles.rank(r.roles.get(target));
  if(!members(r).contains(target)||canonical==null||canonical.equals(roles.top())||target.equals(ruler(r)))return Result.no("Choose a member and a role below Ruler.");
  if(!staff&&(rank<0||(old>=0&&old<=rank)||roles.rank(canonical)<=rank))return Result.no("You must outrank the member and the new role.");
  r.roles.put(target,canonical);return Result.ok("Realm role set to "+canonical+".");
 }
 private Result eligible(Realm r,UUID u,long now){
  Election open=data.realmGovernance.openElections(r.name).stream().findFirst().orElse(null);
  for(var en:r.cities.entrySet()){
   // Eligibility is frozen against city join time when the ballot opens, even with zero configured tenure.
   if(en.getValue()> (open==null?now:open.opens)||(open==null?now:open.opens)-en.getValue()<settings.joinTenureDays()*DAY)continue;
   Land c=book.land(en.getKey()).orElse(null);
   if(c!=null&&cityGov.eligibility(c.name,u,now).ok())return Result.ok("eligible");
  }
  return Result.no("Voting needs vetting, city voting-role tenure and realm join tenure before this election opened.");
 }
 public Result sign(UUID actor,Realm r,long now,boolean staff){
  if(!owner(r,actor)&&!staff)return Result.no("Only the ruler can sign.");Result b=block(r,now,staff,"charter by "+actor);if(!b.ok())return b;
  Result result=elections.signCharter(r.name,ruler(r),Charter.Mode.REPUBLIC,now);if(!result.ok())return result;
  elections.startElection(r.name,ruler(r),ElectionService.OWNER_OFFICE,0,now);return Result.ok("Elected charter signed; ruler election opened.");
 }
 public Result revoke(UUID actor,Realm r,long now,boolean staff){
  if(!owner(r,actor)&&!staff)return Result.no("Only the ruler can revoke.");Result b=block(r,now,staff,"charter revoke by "+actor);if(!b.ok())return b;
  return elections.revokeCharter(r.name,ruler(r),now);
 }
 public Result run(UUID actor,Realm r,long now){if(data.realmGovernance.openElections(r.name).stream().noneMatch(e->now<e.closes))return Result.no("No open realm election.");return elections.run(r.name,actor,now);}
 public Result vote(UUID actor,Realm r,UUID candidate,long now){if(data.realmGovernance.openElections(r.name).stream().noneMatch(e->now<e.closes))return Result.no("No open realm election.");return elections.vote(r.name,actor,candidate,now);}
 private void pruneBallot(Realm r){
  Set<UUID> remaining=members(r);for(Election e:data.realmGovernance.openElections(r.name)){
   e.candidates.removeIf(u->!remaining.contains(u));e.votes.entrySet().removeIf(v->!remaining.contains(v.getKey())||!e.candidates.contains(v.getValue()));
  }
 }
 public void tick(long now){
  data.realmInvites.entrySet().removeIf(e->e.getValue()<=now);
  for(Realm r:list()){
   Set<UUID> current=members(r);r.roles.keySet().removeIf(u->!current.contains(u));pruneBallot(r);
   for(Election e:data.realmGovernance.openElections(r.name)){
    e.candidates.removeIf(u->!eligible(r,u,now).ok());e.votes.entrySet().removeIf(v->!eligible(r,v.getKey(),now).ok()||!e.candidates.contains(v.getValue()));
   }
  }
  elections.tick(now);
  for(Realm r:list()){
   ruler(r);
   // First/no-quorum ballots must also recur; ElectionService normally schedules only filled offices.
   if(charter(r).mode!=Charter.Mode.NONE&&data.realmGovernance.openElections(r.name).isEmpty()
      &&!data.realmGovernance.offices.containsKey(GovData.key(r.name,ElectionService.OWNER_OFFICE))){
    long last=data.realmGovernance.elections.values().stream().filter(e->e.land.equals(r.name)).mapToLong(e->e.closes).max().orElse(0);
    if(now>=last+settings.termDays()*DAY)elections.startElection(r.name,ruler(r),ElectionService.OWNER_OFFICE,0,now);
   }
  }
  if(economy!=null)for(var en:new ArrayList<>(data.pendingRefunds.entrySet()))try{economy.deposit(en.getKey(),en.getValue());data.pendingRefunds.remove(en.getKey());}catch(RuntimeException failure){audit.accept("Refund remains reserved for "+en.getKey());}
 }
 public Result deposit(UUID actor,Realm r,double amount,long now){
  if(economy==null)return Result.no("No economy plugin installed.");if(!members(r).contains(actor))return Result.no("Only realm members can deposit.");
  if(!valid(amount)||!Double.isFinite(r.balance+amount))return Result.no("Choose a positive finite amount.");
  if(economy.balance(actor)<amount||!economy.withdraw(actor,amount))return Result.no("You cannot pay that amount.");
  Bank.record(r,now,actor,amount,"REALM_DEPOSIT");return Result.ok("Deposited "+amount+".");
 }
 public Result withdraw(UUID actor,Realm r,double amount,long now,boolean staff){
  if(economy==null)return Result.no("No economy plugin installed.");if(!has(r,actor,REALM_TREASURY)&&!staff)return Result.no("You need REALM_TREASURY.");
  if(!valid(amount)||amount>r.balance)return Result.no("Choose an amount the treasury can cover.");
  String day=Long.toString(Math.floorDiv(now,DAY));double used=r.withdrawn.getOrDefault(day,0.0);
  if(!Double.isFinite(used+amount))return Result.no("Daily withdrawal total is too large.");
  if(!data.realmGovernance.openElections(r.name).isEmpty()&&used+amount>settings.electionWithdrawCap()){
   Result b=block(r,now,staff,"withdraw cap by "+actor);if(!b.ok())return b;
  }
  try{economy.deposit(actor,amount);}catch(RuntimeException failure){return Result.no("Payout failed; treasury retained.");}
  Bank.record(r,now,actor,-amount,"REALM_WITHDRAW");r.withdrawn.keySet().removeIf(k->!k.equals(day));r.withdrawn.put(day,used+amount);return Result.ok("Withdrew "+amount+".");
 }
 private static boolean valid(double a){return Double.isFinite(a)&&a>0;}
 public Result tax(UUID actor,Realm r,double amount){
  if(!has(r,actor,REALM_TAX))return Result.no("You need REALM_TAX.");
  if(!Double.isFinite(amount)||amount<0||amount>settings.maxTax())return Result.no("Choose a tax from 0 to "+settings.maxTax()+".");
  r.tax=amount;if(amount==0)r.unpaid.clear();tell(r,"Realm daily city tax is now "+amount+".");return Result.ok("Realm tax set.");
 }
 /** Daily idempotence is persisted separately from the existing city day scheduler. */
 public void taxDay(long now){
  if(economy==null)return;long day=Math.floorDiv(now,DAY);if(data.lastRealmDay!=null&&day<=data.lastRealmDay)return;
  for(Realm r:list())for(String name:new ArrayList<>(r.cities.keySet())){
   if(name.equals(key(r.capital))||r.tax<=0){r.unpaid.remove(name);continue;}Land c=book.land(name).orElse(null);if(c==null)continue;
   if(!Double.isFinite(r.balance+r.tax)){announcer.accept(c.name,"Realm treasury cannot receive tax; no payment or strike.");continue;}
   if(c.balance>=r.tax){Bank.record(c,now,null,-r.tax,"REALM_TAX");Bank.record(r,now,null,r.tax,"REALM_TAX");r.unpaid.remove(name);}
   else {int strikes=r.unpaid.merge(name,1,Integer::sum);announcer.accept(c.name,"Owner: realm tax unpaid for "+strikes+" days.");if(strikes>=settings.unpaidDaysKick())detach(r,c);}
  }
  data.lastRealmDay=day;
 }
 private RealmRelation pair(Realm a,Realm b,boolean create){
  String x=key(a.name),y=key(b.name),k=x.compareTo(y)<0?x+"|"+y:y+"|"+x;
  if(create)return data.realmRelations.computeIfAbsent(k,n->{RealmRelation p=new RealmRelation();p.first=x.compareTo(y)<0?x:y;p.second=x.compareTo(y)<0?y:x;return p;});
  return data.realmRelations.get(k);
 }
 public String relation(Realm a,Realm b,long now){
  if(a==null||b==null)return "neutral";if(a==b)return "member";RealmRelation p=pair(a,b,false);
  return p==null?"neutral":p.enemyAt>0&&now>=p.enemyAt?"enemy":p.state;
 }
 public Result relate(UUID actor,Realm from,Realm to,String kind,long now){
  if(!has(from,actor,REALM_RELATIONS))return Result.no("You need REALM_RELATIONS.");
  if(to==null||from==to||!Set.of("ally","truce","enemy","neutral").contains(kind))return Result.no("Choose another realm and a relation.");
  RealmRelation p=pair(from,to,true);
  if(kind.equals("enemy")&&now<p.truceUntil)return Result.no("Truce prevents enemy status until "+Instant.ofEpochMilli(p.truceUntil));
  if(kind.equals("ally")||kind.equals("truce")){p.requested=kind;p.requester=key(from.name);p.expires=now+DAY;tell(to,from.name+" requests "+kind+". Open /c realm for Accept/Decline buttons.");return Result.ok("Relation requested; the other realm must accept.");}
  String before=relation(from,to,now);
  if(kind.equals("enemy")&&(before.equals("enemy")||p.enemyAt>now))return Result.no("Enemy status is already active or scheduled.");
  p.requested=null;p.requester=null;p.enemyAt=kind.equals("enemy")?now+Math.max(1,settings.enemyDelayHours()*3_600_000L):0;
  p.state=kind.equals("enemy")?before:"neutral";if(kind.equals("enemy")&&settings.enemyDelayHours()==0){p.state="enemy";p.enemyAt=0;}String message=from.name+" and "+to.name+": "+kind+(p.enemyAt>0?" from "+Instant.ofEpochMilli(p.enemyAt):"")+".";
  tell(from,message);tell(to,message);return Result.ok(message);
 }
 public Result acceptRelation(UUID actor,Realm receiver,Realm sender,long now){
  if(!has(receiver,actor,REALM_RELATIONS))return Result.no("You need REALM_RELATIONS.");
  RealmRelation p=pair(receiver,sender,false);
  if(p==null||p.requested==null||!key(sender.name).equals(p.requester)||p.expires<=now)return Result.no("No valid relation request.");
  p.state=p.requested;p.requested=null;p.requester=null;p.enemyAt=0;if(p.state.equals("truce"))p.truceUntil=now+settings.truceDays()*DAY;
  tell(receiver,receiver.name+" and "+sender.name+" are now "+p.state+".");tell(sender,receiver.name+" accepted "+p.state+".");return Result.ok("Relation accepted.");
 }
 public Result declineRelation(UUID actor,Realm receiver,Realm sender){
  if(!has(receiver,actor,REALM_RELATIONS))return Result.no("You need REALM_RELATIONS.");RealmRelation p=pair(receiver,sender,false);
  if(p==null||!key(sender.name).equals(p.requester))return Result.no("No relation request.");p.requested=null;p.requester=null;return Result.ok("Relation declined.");
 }
 public List<Realm> requests(Realm r,long now){return list().stream().filter(o->{if(o==r)return false;RealmRelation p=pair(r,o,false);return p!=null&&p.requested!=null&&key(o.name).equals(p.requester)&&now<p.expires;}).toList();}
 public boolean enemy(UUID actor,Realm target,long now){return ofPlayer(actor).stream().anyMatch(r->relation(r,target,now).equals("enemy"));}
 public boolean friendly(UUID actor,Realm target,long now){return target!=null&&!enemy(actor,target,now)&&ofPlayer(actor).stream().anyMatch(r->r==target||relation(r,target,now).equals("ally"));}
 public boolean allyPermission(UUID actor,Land city,Perm p,long now){Realm r=ofCity(city.name).orElse(null);return r!=null&&settings.allyPerms().contains(p)&&!enemy(actor,r,now)&&ofPlayer(actor).stream().anyMatch(other->relation(other,r,now).equals("ally"));}
 public boolean spawnChunk(ChunkPos chunk){return list().stream().anyMatch(r->r.spawn!=null&&r.spawn.chunk().equals(chunk));}
 public boolean pvp(UUID a,UUID b,ChunkPos atA,ChunkPos atB,boolean normal,long now){
  List<Realm> ar=ofPlayer(a),br=ofPlayer(b);
  if(ar.stream().anyMatch(x->br.stream().anyMatch(y->relation(x,y,now).equals("truce"))))return false;
  if(settings.enemyPvp())for(Realm x:ar)for(Realm y:br)if(relation(x,y,now).equals("enemy")){
   Realm areaA=book.landAt(atA).flatMap(l->ofCity(l.name)).orElse(null),areaB=book.landAt(atB).flatMap(l->ofCity(l.name)).orElse(null);
   if((areaA==x||areaA==y)&&(areaB==x||areaB==y))return true;
  }
  return normal;
 }
 public Result setSpawn(UUID actor,Realm r,Spot spot){
  if(!has(r,actor,REALM_SPAWN))return Result.no("You need REALM_SPAWN.");
  if(book.landAt(spot.chunk()).map(l->!l.name.equalsIgnoreCase(r.capital)).orElse(true))return Result.no("Stand in the capital.");
  r.spawn=spot;return Result.ok("Realm spawn set.");
 }
 public Result publicSpawn(UUID actor,Realm r,boolean on){if(!has(r,actor,REALM_SPAWN))return Result.no("You need REALM_SPAWN.");r.publicSpawn=on;return Result.ok("Realm public spawn "+(on?"on.":"off."));}
 public Result fee(UUID actor,Realm r,double amount){if(!has(r,actor,REALM_SPAWN))return Result.no("You need REALM_SPAWN.");if(!Double.isFinite(amount)||amount<0)return Result.no("Choose a finite nonnegative fee.");r.spawnFee=amount;return Result.ok("Realm spawn fee set.");}
 public Result spawn(UUID actor,Realm r,long now){
  if(r==null||r.spawn==null)return Result.no("No realm spawn.");if(enemy(actor,r,now))return Result.no("Enemies cannot use this spawn.");
  Land cap=book.land(r.capital).orElse(null);if(cap==null||cap.banned.contains(actor))return Result.no("You cannot enter the capital.");
  if(!friendly(actor,r,now)&&!r.publicSpawn)return Result.no("This realm spawn is for members and allies.");return Result.ok("Travelling to "+r.name+".");
 }
 public Result arrive(UUID actor,Realm r,long now,BooleanSupplier teleport){
  Result access=spawn(actor,r,now);if(!access.ok())return access;double fee=economy==null||friendly(actor,r,now)?0:r.spawnFee;
  return Arrival.transfer(economy,actor,fee,r.balance,teleport,a->Bank.record(r,now,actor,a,"REALM_SPAWN"),a->data.pendingRefunds.merge(actor,a,Double::sum));
 }
}
