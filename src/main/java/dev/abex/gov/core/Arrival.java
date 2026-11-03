package dev.abex.gov.core;
import java.util.UUID;
import java.util.function.*;
/** Shared city/estate/realm reservation: charge once, credit only on arrival, refund cancellation. */
public final class Arrival {
 private Arrival(){}
 public static Result transfer(EconomyPort economy, UUID actor, double fee, double balance, BooleanSupplier teleport,
                              DoubleConsumer credit, DoubleConsumer refundOwed) {
  if(!Double.isFinite(fee)||fee<0||!Double.isFinite(balance+fee))return Result.no("Invalid destination fee.");
  if(fee>0&&(economy==null||economy.balance(actor)<fee||!economy.withdraw(actor,fee)))return Result.no("You cannot afford the spawn fee.");
  boolean arrived=false;
  try { arrived=teleport.getAsBoolean(); }
  catch(RuntimeException failure){ /* Refund below, including teleport adapter failures. */ }
  if(!arrived){
   if(fee>0)try{economy.deposit(actor,fee);}catch(RuntimeException failure){refundOwed.accept(fee);return Result.no("Teleport cancelled; your refund is reserved until the economy accepts it.");}
   return Result.no("Teleport was cancelled.");
  }
  if(fee>0)credit.accept(fee);
  return Result.ok("Arrived.");
 }
}
