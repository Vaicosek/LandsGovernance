package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import java.time.Instant;
import java.util.*;

/** Player names and clickable consent, all routed through /c. */
final class RealmCommands implements Listener {
 private final GovPlugin plugin; private final LandBook book; private final RealmService realms;
 RealmCommands(GovPlugin plugin,LandBook book,RealmService realms){this.plugin=plugin;this.book=book;this.realms=realms;}
 private String name(UUID id){String n=Bukkit.getOfflinePlayer(id).getName();return n==null?"Unknown player":n;}
 private Realm mine(Player p){
  Land here=book.landAt(ProtectListener.chunk(p.getLocation())).orElse(null);
  if(here!=null&&here.members.containsKey(p.getUniqueId())){Realm r=realms.ofCity(here.name).orElse(null);if(r!=null)return r;}
  List<Realm> mine=realms.ofPlayer(p.getUniqueId());if(mine.size()==1)return mine.getFirst();
  p.sendMessage(Msg.info(mine.isEmpty()?"You aren't in a realm.":"Stand in the member city you mean."));return null;
 }
 private Land owned(Player p){return book.list().stream().filter(l->l.owner.equals(p.getUniqueId())).findFirst().orElse(null);}
 void cityLink(Player p,Land city){cityLink(p,city,true);}
 void cityLink(Player p,Land city,boolean sayNone){
  realms.ofCity(city.name).ifPresentOrElse(r->p.sendMessage(Msg.info(city.name+" realm: ")
   .append(Msg.button(r.name,"/c realm info "+r.name,"View realm"))),()->{if(sayNone)p.sendMessage(Msg.info(city.name+" has no realm."));});
 }
 boolean command(Player p,String sub,String[] args){
  if(!sub.equals("realm")&&!sub.equals("rc"))return false;
  long now=System.currentTimeMillis();UUID actor=p.getUniqueId();boolean staff=p.hasPermission("cities.admin");
  try{
   if(sub.equals("rc")){Realm r=mine(p);if(r!=null&&args.length>1)plugin.features.realmChat(p,r,Component.text(String.join(" ",Arrays.copyOfRange(args,1,args.length))));return true;}
   String action=args.length<2?"info":args[1].toLowerCase(Locale.ROOT);
   if(action.equals("list")){for(Realm r:realms.list())p.sendMessage(Msg.button(r.name,"/c realm info "+r.name,"View realm"));return true;}
   if(action.equals("create")){Land c=owned(p);p.sendMessage(Msg.of(args.length!=3||c==null?Result.no("City owners: /c realm create <name>"):realms.create(actor,c.name,args[2],now)));return true;}
   if(action.equals("accept")||action.equals("decline")){
    Land c=owned(p);if(c==null||args.length!=3){p.sendMessage(Msg.info("Use the invitation's Accept or Decline button."));return true;}
    p.sendMessage(Msg.of(action.equals("accept")?realms.accept(actor,args[2],c.name,now):realms.decline(actor,args[2],c.name)));return true;
   }
   if(action.equals("spawn")){Realm r=args.length==3?realms.realm(args[2]).orElse(null):mine(p);if(r!=null)plugin.features.realmTeleport(p,r.name);else p.sendMessage(Msg.info("No such realm."));return true;}
   if(action.equals("info")){
    pendingInvites(p,now);Realm r=args.length==3?realms.realm(args[2]).orElse(null):mine(p);if(r!=null)info(p,r,now);return true;
   }
   // Ballot buttons carry the realm name as well as the candidate's name for multi-realm members.
   Realm r=action.equals("vote")&&args.length==4?realms.realm(args[3]).orElse(null):action.equals("run")&&args.length==3?realms.realm(args[2]).orElse(null):Set.of("acceptrelation","declinerelation").contains(action)&&args.length==4?realms.realm(args[3]).orElse(null):mine(p);
   if(r==null)return true;
   Result result;
   switch(action){
    case "invite" -> {
     if(args.length!=3){usage(p,"invite <city>");return true;}result=realms.invite(actor,r,args[2],now);
     if(result.ok())book.land(args[2]).ifPresent(c->{Player owner=Bukkit.getPlayer(c.owner);if(owner!=null)invitation(owner,r,c);});
    }
    case "kick" -> {if(args.length!=3){usage(p,"kick <city>");return true;}result=realms.kick(actor,r,args[2],now,staff);}
    case "leave" -> {Land c=owned(p);result=c==null?Result.no("Only a city owner can leave."):realms.leave(actor,r,c.name,now);}
    case "capital" -> {if(args.length!=3){usage(p,"capital <city>");return true;}result=realms.capital(actor,r,args[2],now,staff);}
    case "delete" -> result=realms.delete(actor,r,now,staff);
    case "role" -> {
     if(args.length!=4){usage(p,"role <player> <role>");return true;}OfflinePlayer target=Bukkit.getOfflinePlayerIfCached(args[2]);
     result=target==null?Result.no("No known player by that name."):realms.role(actor,r,target.getUniqueId(),args[3],now,staff);
    }
    case "charter" -> {
     if(args.length==4&&args[2].equalsIgnoreCase("sign")&&args[3].equalsIgnoreCase("elected"))result=realms.sign(actor,r,now,staff);
     else if(args.length==3&&args[2].equalsIgnoreCase("revoke"))result=realms.revoke(actor,r,now,staff);
     else {p.sendMessage(Msg.info("Charter: "+(realms.charter(r).mode==Charter.Mode.NONE?"none":"elected")+" ")
      .append(Msg.button("Sign elected","/c realm charter sign elected","Elect the ruler"))
      .append(Component.space()).append(Msg.button("Revoke","/c realm charter revoke","Start the notice period")));return true;}
    }
    case "run" -> result=realms.run(actor,r,now);
    case "vote" -> {
     if(args.length<3){ballot(p,r);return true;}
     UUID candidate=realms.elections().data().openElections(r.name).stream().flatMap(e->e.candidates.stream()).filter(u->name(u).equalsIgnoreCase(args[2])).findFirst().orElse(null);
     result=candidate==null?Result.no("That player is not standing."):realms.vote(actor,r,candidate,now);
    }
    case "treasury" -> {treasury(p,r);return true;}
    case "deposit","withdraw","tax","fee" -> {
     if(args.length!=3){usage(p,action+" <amount>");return true;}double amount=Double.parseDouble(args[2]);
     result=switch(action){case "deposit"->realms.deposit(actor,r,amount,now);case "withdraw"->realms.withdraw(actor,r,amount,now,staff);case "tax"->realms.tax(actor,r,amount);default->realms.fee(actor,r,amount);};
    }
    case "ally","truce","enemy","neutral","acceptrelation","declinerelation" -> {
     if(args.length!=3 && !(args.length==4&&Set.of("acceptrelation","declinerelation").contains(action))){usage(p,action+" <realm>");return true;}Realm other=realms.realm(args[2]).orElse(null);
     result=other==null?Result.no("No such realm."):action.equals("acceptrelation")?realms.acceptRelation(actor,r,other,now)
      :action.equals("declinerelation")?realms.declineRelation(actor,r,other):realms.relate(actor,r,other,action,now);
     if(result.ok()&&other!=null&&(action.equals("ally")||action.equals("truce")))for(UUID u:realms.members(other)){
      Player target=Bukkit.getPlayer(u);if(target!=null&&realms.has(other,u,Perm.REALM_RELATIONS))relationRequest(target,other,r);
     }
    }
    case "chat" -> {plugin.features.toggleRealmChat(p,r);return true;}
    case "setspawn" -> result=realms.setSpawn(actor,r,Phase1.spot(p.getLocation()));
    case "flag" -> {
     if(args.length!=4||!args[2].equalsIgnoreCase("public")||!Set.of("on","off").contains(args[3].toLowerCase(Locale.ROOT))){usage(p,"flag public <on|off>");return true;}
     result=realms.publicSpawn(actor,r,args[3].equalsIgnoreCase("on"));
    }
    default -> {p.sendMessage(Msg.info("/c realm create, invite, kick, leave, capital, delete, role, charter, run, vote, treasury, deposit, withdraw, tax, ally, truce, enemy, neutral, chat, setspawn, spawn, flag, fee, info, list"));return true;}
   }
   p.sendMessage(Msg.of(result));
  }catch(NumberFormatException failure){p.sendMessage(Msg.info("Use a finite number."));}
  return true;
 }
 private void usage(Player p,String text){p.sendMessage(Msg.info("Usage: /c realm "+text));}
 private void invitation(Player p,Realm r,Land city){p.sendMessage(Msg.info(city.name+" is invited to "+r.name+" for one day. ")
  .append(Msg.button("Accept","/c realm accept "+r.name,"Join realm"))
  .append(Component.space()).append(Msg.button("Decline","/c realm decline "+r.name,"Decline invitation")));}
 private void pendingInvites(Player p,long now){Land city=owned(p);if(city==null)return;for(Realm r:realms.list())
  if(realms.data().realmInvites.getOrDefault(r.name.toLowerCase(Locale.ROOT)+"|"+city.name.toLowerCase(Locale.ROOT),0L)>now)invitation(p,r,city);}
 private void relationRequest(Player p,Realm receiver,Realm sender){p.sendMessage(Msg.info(sender.name+" requests "+realms.relationRecord(receiver,sender).requested+" with "+receiver.name+". ")
  .append(Msg.button("Accept","/c realm acceptrelation "+sender.name+" "+receiver.name,"Accept relation"))
  .append(Component.space()).append(Msg.button("Decline","/c realm declinerelation "+sender.name+" "+receiver.name,"Decline relation")));}
 @EventHandler public void join(PlayerJoinEvent event){plugin.features.refreshChatColors();Player p=event.getPlayer();long now=System.currentTimeMillis();pendingInvites(p,now);for(Realm r:realms.ofPlayer(p.getUniqueId()))if(realms.has(r,p.getUniqueId(),Perm.REALM_RELATIONS))for(Realm sender:realms.requests(r,now))relationRequest(p,r,sender);}
 private void treasury(Player p,Realm r){
  if(!realms.has(r,p.getUniqueId(),Perm.REALM_TREASURY)){p.sendMessage(Msg.info("You need REALM_TREASURY."));return;}
  p.sendMessage(Msg.info(r.name+" treasury: "+r.balance));
  for(int i=r.ledger.size()-1;i>=Math.max(0,r.ledger.size()-10);i--){LedgerEntry e=r.ledger.get(i);p.sendMessage(Msg.info(Instant.ofEpochMilli(e.time())+" "+e.amount()+" "+e.reason()));}
 }
 private void ballot(Player p,Realm r){
  for(Election e:realms.elections().data().openElections(r.name)){
   p.sendMessage(Msg.info("Ruler election closes "+Instant.ofEpochMilli(e.closes)+". ")
    .append(Msg.button("Stand","/c realm run "+r.name,"Stand for Ruler")));
   for(UUID candidate:e.candidates)p.sendMessage(Msg.info(name(candidate)+" ("+e.tally().get(candidate)+") ")
    .append(Msg.button("Vote","/c realm vote "+name(candidate)+" "+r.name,"Vote or change your vote")));
  }
 }
 private void info(Player p,Realm r,long now){
  p.sendMessage(Msg.info(r.name+" — ruler: "+name(realms.ruler(r))+"; capital: "+r.capital+"; daily tax: "+r.tax+"; charter: "+(realms.charter(r).mode==Charter.Mode.NONE?"none":"elected")));
  if(realms.charter(r).revokeAt>0)p.sendMessage(Msg.info("Charter ends "+Instant.ofEpochMilli(realms.charter(r).revokeAt)));
  if(realms.has(r,p.getUniqueId(),Perm.REALM_TREASURY))treasury(p,r);
  for(String city:r.cities.keySet())book.land(city).ifPresent(c->p.sendMessage(Msg.info(c.name+(r.unpaid.getOrDefault(city,0)>0?"; unpaid realm tax days: "+r.unpaid.get(city):"")+" ").append(Msg.button("Spawn","/c spawn "+c.name,"Visit city"))));
  p.sendMessage(Msg.info("Realm spawn: "+(r.publicSpawn?"public":"members and allies")+"; visitor fee: "+r.spawnFee+" ")
   .append(Msg.button("Spawn","/c realm spawn "+r.name,"Visit realm")));
  for(Realm other:realms.list())if(other!=r){String relation=realms.relation(r,other,now);RealmRelation pair=realms.relationRecord(r,other);
   p.sendMessage(Component.text(other.name+": "+relation+(pair!=null&&pair.enemyAt>now?"; enemy from "+Instant.ofEpochMilli(pair.enemyAt):""),relation.equals("ally")?NamedTextColor.GREEN:relation.equals("enemy")?NamedTextColor.RED:NamedTextColor.GRAY)
    .append(Component.space()).append(Msg.button("Info","/c realm info "+other.name,"View realm")));}
  if(realms.has(r,p.getUniqueId(),Perm.REALM_RELATIONS))for(Realm sender:realms.requests(r,now))relationRequest(p,r,sender);
  ballot(p,r);
 }
 List<String> complete(Player p,String[] args){
  List<String> choices=List.of();String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"";
  if(args.length==2)choices=List.of("create","invite","kick","leave","capital","delete","role","charter","run","vote","treasury","deposit","withdraw","tax","ally","truce","enemy","neutral","chat","setspawn","spawn","flag","fee","info","list");
  else if(args.length==3&&Set.of("invite","kick","capital").contains(action))choices=book.list().stream().map(l->l.name).toList();
  else if(args.length==3&&Set.of("ally","truce","enemy","neutral","spawn","info","accept","decline","run").contains(action))choices=realms.list().stream().map(r->r.name).toList();
  else if(args.length==3&&action.equals("role"))choices=realms.ofPlayer(p.getUniqueId()).stream().flatMap(r->realms.members(r).stream()).distinct().map(this::name).toList();
  else if(args.length==4&&action.equals("role"))choices=realms.roles().names();
  else if(args.length==3&&action.equals("vote"))choices=realms.ofPlayer(p.getUniqueId()).stream().flatMap(r->realms.elections().data().openElections(r.name).stream()).flatMap(e->e.candidates.stream()).distinct().map(this::name).toList();
  else if(args.length==3&&action.equals("charter"))choices=List.of("sign","revoke");
  else if(args.length==4&&action.equals("charter"))choices=List.of("elected");
  else if(args.length==3&&action.equals("flag"))choices=List.of("public");
  else if(args.length==4&&action.equals("flag"))choices=List.of("on","off");
  String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
 }
}
