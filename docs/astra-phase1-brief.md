# Job: LandsGovernance phase 1 (spawn, map, flags, bank, upkeep, tax, ban, chat, borders, list/info, admin bypass)

Work dir: `C:\work\MInecraft Server\LandsGovernance` (Paper 26.3 plugin, Java 25, Gradle). Read `ROADMAP.md` (phase 1 rows),
`DESIGN.md` (v0.2 section) and the code in `src/main/java/dev/abex/gov/` first. Match the style: plain-Java rules in
`core/` (no Bukkit imports, unit-tested), Paper glue in `bukkit/`, `Result.ok/no` with short plain-English messages,
public fields on data classes (Gson JSON in `GovData`), comments only where the why isn't obvious.

John's direction: "we will just make it better" than Lands and Towny, not copy them. So: one command (`/land ...`),
every choice in chat is a clickable button (players never type IDs or coordinates), and power stays answerable to
elections (a role's perms decide who may do what; nothing is hard-wired to the owner unless said below).

## Test first
Write the core tests before the core code, in new files under `src/test/java/dev/abex/gov/core/` (e.g.
`Phase1Test.java`), using the style of `LandBookTest`. **Do not edit or delete existing tests**; they must keep passing.
You can't run Gradle; Claude compiles and runs everything after you, so be exact with types and imports.

## New perms
Add to `Perm`: `SETTINGS` (spawn, flags, tax rate), `BANK` (withdraw), `BAN`. Update config `roles:` defaults:
Owner and Count get all; Admin gets all but SET_ROLES and BANK; Citizen and Member unchanged.

## Features (core rules + Paper glue)
1. **Spawn.** `Land.spawn` (world, x, y, z, yaw, pitch; a small `Spot` record). `/land setspawn` (SETTINGS, must stand in
   the land; create sets it to where the owner stood). `/land spawn [land]`: members always; others only if the land flag
   `public` is on. 3 s warm-up that cancels if the player moves (config `spawn.warmup-seconds`).
2. **Map.** Core `ClaimMap.render(LandBook, ChunkPos center, UUID viewer, int radius)` returns rows of cells:
   YOU, OWN_LAND, OWN_PLOT (viewer's plot), OTHER_LAND, WILD. Paper: `/land map` prints 9 rows × 17 columns of `■`
   coloured (you = yellow, own = green, own plot = aqua, other land = red, wild = dark gray), north up, hover shows the land
   name, and clicking a WILD cell that touches your land runs `/land claimat <x> <z>` (claims that chunk if the player has
   CLAIM and is within 8 chunks of it). That's the "better": claim by clicking the map.
3. **Flags.** `Land.flags` map, defaults from config `flags:`: `pvp: false, monsters: true, fire: false,
   explosions: false, public: false, visitor-interact: false, visitor-containers: false`. `/land flag` shows each flag as a
   clickable [on]/[off] toggle; `/land flag <name> <on|off>` (SETTINGS). Wire them: pvp between players inside the land;
   monsters = natural/spawner spawns of Monsters in the land (CreatureSpawnEvent, not spawn eggs by members);
   fire/explosions replace the current always-off behaviour (off = protected, as now); visitor-* let non-members INTERACT
   / use CONTAINERS (plots still owner-only for containers). `LandBook.can` must take the visitor flags into account.
4. **Bank.** Core `Bank` logic on `Land.balance` (double). An `EconomyPort` interface in core (`balance(UUID)`,
   `withdraw(UUID, double)` returns boolean, `deposit(UUID, double)`) with a fake in tests. `/land deposit <amount>` (any member),
   `/land withdraw <amount>` (BANK), `/land bank` (balance + last 10 entries of a `Land.ledger` list: time, who, amount, reason;
   keep at most 50). Paper: Vault economy via `RegisteredServiceProvider<net.milkbowl.vault.economy.Economy>`; add
   `compileOnly 'com.github.MilkBowl:VaultAPI:1.7.1'` and `maven { url = 'https://jitpack.io' }`, `softdepend: [Vault]`.
   No economy installed -> bank, tax and upkeep commands answer "No economy plugin installed." and upkeep/tax are skipped.
5. **Upkeep.** Daily at config `economy.day-hour` (server time). Each land pays `chunks × economy.upkeep-per-chunk` from its
   bank. Can't pay: everything it has goes, the land gets a strike and members are told; after `economy.upkeep-grace-days`
   (3) strikes in a row, the newest non-spawn chunk is unclaimed each further day (never the last chunk; never delete).
   Paying in full resets strikes. Warn members one day ahead if the bank won't cover tomorrow.
6. **Tax.** `/land tax <amount>` (SETTINGS) sets a daily member tax (0 = off, max `economy.max-tax`). At day processing each
   member except the owner pays into the bank; a member who can't pay gets a strike; after `economy.tax-grace-days` (3)
   strikes they are told and listed in `/land` for the owner, **not** auto-kicked (better than Towny: no silent purges; and
   kicking still goes through the election guard). Order of day processing: taxes in, then upkeep out.
   Core: `Economy day = new DayProcessor(...)`-style class with `runDay(long now)`, fully unit-tested with the fake economy.
7. **Ban.** `/land ban <player>` / `/land unban <player>` (BAN, must outrank if the target is a member; a member is removed
   first, through the same guard as kick). Banned players can't enter: on move into the land they are pushed back to the
   chunk they came from with an action-bar message. `/land` shows the ban list with [unban] buttons.
8. **Chat.** `/land chat` toggles land chat (your next normal chat lines go only to members of the land you are in, or your
   only land); `/land chat <message>` sends one line. Use Paper's `AsyncChatEvent`.
9. **Borders.** `/land show`: 10 s of particles along the chunk borders of claimed chunks within 4 chunks of the player, at
   the player's eye height ±1, green for own land, red for others.
10. **List / info.** `/land list`: lands sorted by members, 10 per page, clickable names -> `/land info <name>`.
    `/land info <name>`: owner, members count, chunks, charter, nation (blank for now), open elections, flags that matter
    to visitors, and a [Spawn] button if public.
11. **Admin.** `/land admin bypass` toggles bypass for that staff member (protection ignores them);
    `/land admin delete <land>` deletes regardless of guard (voids open elections, logs it).

Update `config.yml` and `test-server/config-solo-test.yml` with the new keys (solo test: upkeep 0, tax max 100,
warmup 0). Update `README.md` (command table, short) and tick the phase-1 rows in `ROADMAP.md`.

## Done means
New core tests cover: spawn access by public flag, map cells, each flag through `can`, deposit/withdraw perms and
balance, upkeep strikes and unclaim order (never last chunk), tax strikes without kicking, ban (member removed, guard
respected), list ordering. All old tests still pass. End with a report: files changed, which tests you added, anything
you couldn't wire or weren't sure exists in Paper 26.3.
