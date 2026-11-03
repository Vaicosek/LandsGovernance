# Job: Cities shops, step 3 (plugin side): sale feed + log polish + break test

Work dir: `C:\work\MInecraft Server\LandsGovernance`. You can't run Gradle; Claude compiles and tests after you. Never edit
existing tests; new tests in new files, each failing on today's code. Copy no ChestShop/Notifier code.
A second Astra job is building the receiving endpoint in `C:\work\MInecraft Server\AbexTech` at the same time; don't touch that folder.

## 1. Feed (wire contract, must match exactly)
After every successful trade (where `ShopService` records a `Sale`), queue the sale for sending. Sending is async (never on the main thread),
`POST <feed.url>` with JSON body:
`{"event_id":"<server-id>:<sale id>","time_ms":..,"server":"<server-id>","shop":"world:x:y:z","owner_uuid":..,"owner_name":..,
"city":null|"name","player_uuid":..,"player_name":..,"side":"BUY"|"SELL","item":"diamond","qty":2,"price":10.0}`
(BUY = the player bought from the shop.) Headers: `X-Cities-Market: <feed.market-id>`, `X-Cities-Time: <unix ms>`,
`X-Cities-Signature: <hex HMAC-SHA256 of (time + "." + body) with feed.key>`.
Config (`config.yml`, all empty = feed off): `feed.url`, `feed.market-id`, `feed.key`, `feed.server-id` (default "cities").
2xx = delivered; 409 = duplicate, also delivered. Anything else or a network error: keep it queued and retry with backoff
(30 s → 10 min cap). The queue is persisted in `GovData` (survives restart), capped at 5,000 (drop oldest, log a warning).
Never put the key in logs. Put the pure parts (JSON body, signature, backoff schedule, queue) in `core` and unit-test them,
including a known-answer HMAC test vector.

## 2. Log polish (players see it)
`/c shop log` and the join summary: lines like `14:32 · CityBot2 bought 2 diamond for 10.00 · Shop @ 3,-59,10`. Use the server's
local time (`HH:mm`, and `dd.MM HH:mm` when not today), short coords without the world name, and no trailing empty line.
Low stock: `Low stock: diamond · Shop @ 10,-59,4`.

## 3. Break test
A MockBukkit test (see `src/test/java/dev/abex/gov/bukkit/ShopSignSalesTest.java` for setup; MockBukkit doesn't keep chest contents
between `getState()` calls) where a non-owner breaks the shop chest and then its sign: both `BlockBreakEvent`s are cancelled
and the shop still exists; the owner breaking the chest closes the shop.

Report: files changed, tests added, what you could not check.
