# Job: Cities phase 2: realms (nations with an elected ruler, treasury, tax, relations, chat, spawn)

Work dir: `C:\work\MInecraft Server\LandsGovernance`. Phases 1 and 1b (spawn, treasury, flags, upkeep, tax, estates, rename to `/c`)
are in the code: read them first and build on them (same `Spot`, `EconomyPort`, ledger, warm-up, `ElectionService`,
`Charter`, `DayProcessor`, `JsonStore`). Same rules as before: core tests first in a new test file `RealmsTest.java`
(plus a persistence test), never edit existing tests, `Result.ok/no`, everything under the one `/c` command, clickable
chat, no typed IDs (names with tab-complete are fine, an accept is always a [Accept] button). You can't run Gradle;
Claude compiles and tests after you. Player words: city, lot, estate, **realm**, treasury. Never "nation", "town", "land".

## Why (John 2026-11-03)
"the plugin has to have what city plugin has and town plugin has and nations". Our edge over both: the realm's ruler
answers to elections, with the same vetting, tenure and abuse blocks cities already have.

## Realm basics
- `/c realm create <name>`: city owner, city not in a realm, costs `realms.create-cost` from the **city treasury**
  (refused, nothing charged, if short; free with no economy). That city is the **capital**; its owner is the **ruler**.
  Names unique across realms (case-insensitive), same name rules as cities.
- `/c realm invite <city>` (REALM_INVITE): the city's owner gets a clickable [Accept]/[Decline]; expires in 1 day.
  `/c realm kick <city>` (REALM_KICK), `/c realm leave` (city owner; the capital can't leave).
- `/c realm capital <city>` (ruler): move the capital to a member city. `/c realm delete` (ruler): cities become free,
  treasury paid into the capital's city treasury.
- Capital city deleted (any reason: owner, upkeep, admin): the member city with the most members becomes capital; none
  left -> realm deleted, treasury to nobody is not allowed: pay it to the old capital owner's account.
- `/c realm` shows your realm: ruler, capital, cities (each with a [Spawn] button), treasury (to those with
  REALM_TREASURY), tax, relations, open realm election with buttons. `/c realm info <realm>`, `/c realm list`.

## Realm roles (real power, like city roles)
Config lists realm roles highest first, each with perms `REALM_INVITE, REALM_KICK, REALM_TREASURY, REALM_TAX,
REALM_RELATIONS, REALM_SPAWN, REALM_ROLES`. Defaults: Ruler (all), Chancellor (all but REALM_ROLES), Steward
(REALM_TREASURY, REALM_TAX), Envoy (REALM_RELATIONS, REALM_INVITE). `/c realm role <player> <role>` (REALM_ROLES,
must outrank): any member of a member city. Removing a city from the realm drops its members' realm roles.

## Realm charter and elections (our edge)
Reuse `Charter` and `ElectionService`. Realm charter: `none` (ruler = capital owner, as above), `elected` (the Ruler
office is filled by election every `realms.term-days`, default 14).
- `/c realm charter sign elected` (ruler), revoke by the same rules as city charters (notice period, members told).
- Voters and candidates: eligible members (vetted + tenure in their city role, same rules as city elections) of **any**
  member city. One vote each, changeable until close, clickable vote list.
- Elected ruler gets the Ruler role the moment they win; the capital owner keeps owning their city but stops being
  ruler. Ties: earlier candidate wins (same as cities).
- Abuse blocks while a realm election is open: no kicking member cities, no moving the capital, no deleting the realm,
  no changing the charter, no realm role changes, treasury withdrawals capped at `realms.election-withdraw-cap` per day.
  Each refusal tells the player why ("blocked: realm election open until <time>"). Staff (`cities.admin`) bypass, logged.
- A city joining during an open realm election can't vote in it (tenure in the realm counted from join time,
  `realms.join-tenure-days`, default 7). This stops a ruler importing friendly cities before a vote.

## Treasury and realm tax
- `/c realm treasury`, `/c realm deposit <amount>` (any realm member), `/c realm withdraw <amount>` (REALM_TREASURY).
  Same ledger as city treasuries (entry kind `REALM_*`).
- `/c realm tax <amount>` (REALM_TAX, max `realms.max-tax`): daily, each member city except the capital pays it from its
  **city treasury** into the realm treasury, in `DayProcessor` **after** member tax and city upkeep. A city that can't
  pay pays nothing that day, gets a warning to its owner, and after `realms.unpaid-days-kick` (default 3) days in a row
  leaves the realm automatically (members told).
- Money is never created or lost: every transfer is one withdraw + one deposit; if the deposit fails, refund. Test it.

## Relations
`/c realm ally|truce|enemy|neutral <realm>` (REALM_RELATIONS). Default neutral.
| Relation | How it's set | Effect |
|---|---|---|
| ally | request + [Accept] by the other realm's REALM_RELATIONS holder; both sides | members get `relations.ally-perms` (default `INTERACT`) in each other's cities; can use each other's city and realm spawns as members (no fee); green in map/chat |
| truce | request + accept, both sides | no PvP between them even where a city's pvp flag is on; can't go to enemy for `relations.truce-days` (default 3) |
| enemy | one side declares, takes effect after `relations.enemy-delay-hours` (default 24), both realms told | members of the two realms can PvP each other inside each other's cities even with pvp off (`relations.enemy-pvp`, default true); can't use each other's spawns; red in map/chat |
| neutral | either side, at once (breaking ally/truce tells the other side) | nothing extra |
Relations go both ways in storage (one record per pair). A realm deleted -> its relations removed.

## Chat and spawn
- `/c realm chat` toggles realm chat (like city chat), `/c rc <message>` sends one message. Prefix shows the realm name.
- `/c realm setspawn` (REALM_SPAWN, standing in the capital). `/c realm spawn [realm]`: members and allies always;
  others only if the realm flag `public` is on (`/c realm flag public on|off`), paying `realms.spawn-fee` set by
  `/c realm fee <amount>` into the realm treasury (charged once, after the warm-up, only if the teleport happens; same code
  path as city/estate fees). Enemies never.

## Show it
- `/c` and `/c info <city>` show the city's realm (clickable to `/c realm info`).
- Map: cities of your realm one colour, allies green, enemies red; hover shows realm name.
- Entering a city: action bar shows "<city> (<realm>)".

## Done =
New core tests for: create costs the city treasury and refuses when short; unique names; invite needs accept and expires;
capital can't leave; capital deleted -> new capital, realm deleted when empty and treasury not lost; realm tax moves money
from city to realm treasury exactly once a day, skips the capital, kicks after N unpaid days, total money conserved;
elected charter: ruler changes to the winner; abuse blocks during a realm election (kick city, move capital, delete,
charter, roles, withdraw cap); city joining mid-election can't vote; ally needs both sides and gives ally perms; enemy
delay and PvP rule; truce blocks enemy for N days; realm spawn access (member, ally, public visitor pays once, enemy
refused); persistence round-trip. All old tests still pass. Update README (short rows), ROADMAP (phase 2 rows ✅ with
"awaits compile and server verification"), DESIGN (new "Realms" section near the top), config.yml keys with comments.
End with a short report: what you changed (paths), how you checked it, what you could not check.
