package dev.abex.gov.core;
/** One unordered pair; pending requests and truce locks survive a neutral declaration. */
public final class RealmRelation {
 public String first,second;
 public String state="neutral",requested,requester;
 public long expires,enemyAt,truceUntil;
}
