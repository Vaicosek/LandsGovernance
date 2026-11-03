package dev.abex.gov.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Same vector as AbexTech test_cities_feed.py, so the sender and Restocker's receiver can't drift apart. */
class SaleFeedRestockerVectorTest {
 @Test void matchesRestockerReceiverVector() {
  assertEquals("4284e27cb13c82554d08fc92e741b8ae1c126aa41c93a3060481666c36909b51", SaleFeed.signature("k", 1700000000000L, "{\"a\":1}"));
 }
}
