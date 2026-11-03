# Job: Cities shops, step 2: sales log + notifier + click test

Work dir: `C:\work\MInecraft Server\LandsGovernance` (Paper 26.3 plugin, Java 25). You can't run Gradle; Claude compiles,
tests and runs the live bot check after you. Never edit existing tests; put new tests in new files. Each new test must fail
on today's code. Copy no code from ChestShop or ChestShop Notifier (ideas only). Player words: city, shop, treasury.

Read first: `src/main/java/dev/abex/gov/core/ShopService.java`, `Shop.java`, `GovData.java`,
`src/main/java/dev/abex/gov/bukkit/ShopCommands.java`, `src/test/java/dev/abex/gov/core/ShopTest.java`.
For what the notifier must do, read John's CSN mod (it scraped `/csn history`): `C:\work\projects\Sales\CSN_PROJECT_CONTEXT.md`
and the mod source it points to. Don't edit anything in `C:\work\projects\Sales`.

1. **Sales log (core).** Every successful `ShopService.buy`/`sell` appends a `Sale` row to `GovData.sales`: event id
   (unique, never reused, from `GovData.nextId` or a new counter), time, shop key, owner UUID, city (or null), the trading player's UUID,
   side (BUY = player bought from the shop, SELL = player sold to it), item, qty, price. Failed trades log nothing. Rows survive
   save/load. Keep the newest 10,000 rows. Add `seen` per owner (last read time) for the notifier.
2. **Notifier.** On join, if the player has unread sales for their shops (player shops, plus city shops they have treasury rights on),
   send one line, "While you were away: N sales, +X / -Y", with a clickable button running `/c shop log`. `/c shop log [page]` lists your
   sales newest first, 10 per page, Previous/Next buttons, and marks them read. Staff (`cities.admin`) can do `/c shop log <player>`.
   Also a low-stock line in the join summary for shops whose chest has fewer items than one trade (Bukkit side; skip unloaded chunks).
3. **Click test.** Move the right-click decision out of `ShopCommands.onInteract` into a pure method (e.g. `ShopClick.decide(boolean canAccess,
   boolean bypass, boolean sneaking)` → OPEN, BUY, SELL) and unit-test it: owner/bypass → OPEN, other → BUY, other + sneaking → SELL.
   If MockBukkit for Paper 26.3 is on Maven Central, also add a MockBukkit test that fires `PlayerInteractEvent` RIGHT_CLICK_BLOCK on a shop
   sign and checks that the money moved; if it isn't available, say so and skip that part.
4. **Tutorial tip.** The first time a player gets the join summary, add a one-time tip (use the existing `tip(...)` in ShopCommands)
   explaining what the summary is and that `/c shop log` shows it all.
5. Update `/c shop` help text and tab completion (`GovCommand.onTabComplete`) for `log`.

Report: files changed, tests added, what you could not check.
