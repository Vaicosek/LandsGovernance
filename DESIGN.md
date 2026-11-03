# City governance plugin: design

## Realms (phase 2)
Realms group existing cities. The founding city is the capital; its treasury pays the configured creation cost.
Names have city syntax and a separate case-insensitive realm namespace. City owners accept one-day invitations through buttons.
Realm roles grant REALM_INVITE, REALM_KICK, REALM_TREASURY, REALM_TAX, REALM_RELATIONS, REALM_SPAWN and REALM_ROLES;
assignment requires outranking both the member and the proposed role. Departing cities lose their assigned realm roles.

Charter none follows the capital owner. Charter elected reuses Charter and ElectionService in a separate persisted
realm namespace and elects the ruler every 14 days. Winning grants the top realm role without changing any city owner.
Vetting and continuous city voting-role tenure remain authoritative. City realm join tenure is measured at ballot opening;
newly imported cities cannot join that ballot. One changeable vote per player, ties by earlier candidacy.
Open ballots block kicks, capital moves, deletion, charter/role changes, and cap total daily withdrawals.
Staff bypasses are logged. Revocation uses the city charter notice period and tells all member cities.

Realm tax runs in DayProcessor after member tax and upkeep. Each non-capital city pays in full or pays nothing;
three consecutive failures remove it. Both treasuries record internal transfers as REALM_* ledger entries.
The persisted realm day prevents repeated tax collection. Deletion pays the capital treasury; deleting the capital selects
the largest remaining city, or pays the old capital owner when none remain. Failed payouts retain state.

One unordered relation record stores both sides. Ally and truce require clickable consent. Allies receive configured city
permissions without overriding private lots/estates, and use spawns free. Truces prevent mutual PvP and preserve a lock against
enemy declarations even through neutral. Enemy status activates after its delay, blocks spawns, and optionally overrides PvP
flags within the opposing realms' cities. Deleting a realm clears its pair records.

Realm travel shares the city/estate warm-up and Arrival reservation/refund path. Access is rechecked at arrival; public visitor
fees credit the destination only on success. Failed refunds remain persisted liabilities until the economy accepts repayment.
Realm chat, clickable city/realm information, map colours and entry action bars expose membership to players.
All phase 2 code awaits compile and server verification.

## v0.2: our own claims (John 2026-11-03: "I am making my own server")
Cities does the claiming itself, with governance built in. The integration sections further down are
v0.1 history; the election, vetting and charter rules are unchanged and now run on our own cities.

**One command: `/c`** (the old `/gov` subcommands move under it).
| Command | Who (needs) | Effect |
|---|---|---|
| `/c` | anyone | city you stand in + your status, open elections with buttons |
| `/c create <name>` | not owning a city | new city, claims the chunk you stand in, you are Owner |
| `/c claim` / `/c unclaim` | CLAIM | claim the chunk you stand in (must touch your city); unclaim it |
| `/c invite <player>` / `/c join <city>` | INVITE / invited player | invite (expires in 1 day) / accept, you start as the lowest role |
| `/c kick <player>` | KICK, must outrank | removed. Blocked mid-election if they can vote or stand |
| `/c role <player> <role>` | SET_ROLES, must outrank old and new role | change a member's role |
| `/c lot give <player>` / `/c lot revoke` | PLOTS | the chunk you stand in becomes that member's lot / stops being one. Under a signed charter only the lot owner can give a lot up |
| `/c leave`, `/c delete`, `/c owner <player>` | member / owner / owner | delete and hand-over blocked mid-election |
| `/c vet`, `elect`, `run`, `vote`, `cancel`, `charter`, `admin` | as in v0.1 | same rules as before |

**Roles give real power.** Config lists roles from highest to lowest, each with perms:
`BUILD, CONTAINERS, INTERACT, INVITE, KICK, CLAIM, SET_ROLES, PLOTS`. Defaults: Owner (all), Count (all),
Admin (all but SET_ROLES), Citizen (BUILD, CONTAINERS, INTERACT, INVITE), Member (INTERACT). An elected Count gets
the Count perms the moment they win.

**Lots = owning things matters.** In a lot chunk only the lot owner can BUILD and use CONTAINERS, not even the city owner;
INTERACT (doors, buttons) follows city roles. Lot count is the base for "property weight" later.

**Protection (non-members, and members lacking the perm):** block break and place, buckets, containers, doors, buttons and levers,
item frames, armor stands, animals, explosions removing claimed blocks, fire spread, endermen, pistons and liquids
crossing from one owner into another (a different city, lot or the wilderness).

**Limits:** a city may hold `base-chunks + chunks-per-member × members` chunks (default 8 + 4 per member).
New claims must touch the city.

## Estates (phase 1b)
An estate is a named claim-in-a-claim inside a city, with its own owner, trusted builders, spawn, fee and treasury.
Estate chunks remain city chunks and count toward the same limit; they consume no additional city slot.
Names are unique across cities and estates. Only the estate owner and trusted players build or use containers;
INTERACT follows city roles and visitor flags. A public estate can welcome visitors inside a private city.
Members travel free. Visitor fees enter the destination treasury only after successful teleportation.
Leaving, kicking, banning or city deletion pays estate balances to their owners and returns estate chunks to plain city chunks.
A failed payout retains the affected estate and membership/city. Signed charters reserve estate deletion and chunk surrender to its owner.
Upkeep skips estate chunks so it cannot bypass the charter or orphan a spawn.

# v0.1 (on top of Lands)

Target: Paper 26.3 (Java 25) with Lands, through the public Lands API `com.incredibleplugins:lands-api:9.0.0`
(repo.incredibleplugins.com). Lands itself is a paid plugin; the API jar is free.

## What Lands already gives us (checked in the API jar)
| Need | Lands API |
|---|---|
| Members of a claim | `Land.getTrustedPlayers()`, `Area.isTrusted(uuid)` |
| Roles (Count, Admin, Citizen, Member) | `Area.getRoles()`, `Area.getRole(uuid)`, `Area.getRole("Count")`, **`Area.setRole(uuid, role)`** |
| Roles giving power | already: each Lands role carries its own flags. Winning = getting that role = getting its power |
| Claim in a claim | Lands **sub-areas** (`Land.getAllAreas()`; each `Area` has its own owner and roles) |
| Owner deletes the claim | `LandDeleteEvent` is **cancellable** and has a reason (DEFAULT, ADMIN, UPKEEP, INACTIVITY, WAR_CAPTURED, ...) |
| Owner purges voters | `LandUntrustPlayerEvent` is **cancellable** |
| Owner hands land away | `LandOwnerChangeEvent` (cancellable) |
| Nations | `Land.getNation()` |

There's no "role changed" event, so the plugin records role start times itself (checked on join plus every 5 minutes).

## Player flow (one command, `/gov`)
| Command | Who | Effect |
|---|---|---|
| `/gov` | anyone | your status: vetted or not, your roles and how long you've held them, open elections in your lands (clickable) |
| `/gov vet` | anyone | get vetted if you meet the rules (below); otherwise shows what's missing |
| `/gov elect <role> [hours]` | land owner | opens an election for that Lands role (default 48 h) |
| `/gov run` | eligible member | stand as candidate |
| `/gov vote` | eligible member | clickable list of candidates, one vote, can change it until the election closes |
| `/gov charter`, `/gov charter sign council`, `/gov charter sign republic`, `/gov charter revoke` | land owner | show, sign or start revoking the charter |
| `/gov cancel` | land owner | cancels; every member gets told who cancelled it ("civil war" is then up to players) |
| `/gov admin vet/unvet <player>`, `/gov admin end <land>` | staff | overrides |

**Eligible** (to vote or run) = vetted + trusted in the land + has held a voting role (config: e.g. Citizen or higher)
for at least `tenure-days` (default 7). Roles handed out the day before a vote don't count.

## Vetting (login stays optional)
MVP: automatic, with no website and no login. An account is vetted when it has ≥ `min-playtime-hours` (default 10) **and**
no other vetted account has used the same IP (we store a salted hash, not the IP itself). Staff can vet or unvet by hand.
Later: DiscordSRV linked account as a second way in (that's the "Discord pairing"), or a website code.
Unvetted players play normally; they just can't vote or run.

## Abuse cases
| Case | Plugin does |
|---|---|
| Owner deletes the claim mid-election | delete is **blocked** while an election is open (better than rollback: a deleted land can't be restored through the API). Staff, upkeep and war deletes still go through; the election is voided and logged |
| Owner untrusts voters mid-election | untrust of anyone eligible is blocked until the election closes |
| Owner trusts 50 PvPers before a vote | they fail the tenure check |
| 20 alts | not vetted (same IP / no playtime) |
| Owner wins 50.01% then cancels all future elections | depends on the land's charter (below). No charter: allowed and announced. Signed: blocked, and revoking takes `charter-revoke-days` notice |
| Owner transfers ownership mid-election | blocked until it closes |

## Charter (John's pick, 2026-11-03)
Each land has a charter. The owner chooses it; staff never have to.
| Mode | Owner can cancel elections | Who is elected | Leaving it |
|---|---|---|---|
| `none` (default) | yes, announced to every member | roles under the owner | n/a |
| `council` | no | roles under the owner, on a fixed term (`term-days`) | `/gov charter revoke`: takes effect after `charter-revoke-days` (default 14), announced to members |
| `republic` | no | the owner too: the winner of the `owner` election gets the land (`Land.setOwner`) | same notice period (v0.1: whoever owns the land now can start it) |

What players see: in a signed land, the owner's power is limited in public and on a timer. A founder who wants to keep the
land forever simply doesn't sign. Signing becomes the thing that attracts members ("we're a republic, your vote counts").

Plurality wins (ties: longest tenure). Winner gets the Lands role via `setRole`. The previous holder of that elected office
goes back to the role they had before winning. Minimum turnout (`min-voters`, default 3), otherwise no change.

## Data model (one JSON file, `plugins/<name>/data.json`)
- `players`: uuid → { vetted, vettedBy, ipHash, playtime }
- `tenure`: (landId, areaName, uuid) → { role, since }
- `elections`: id → { landId, area, role, opens, closes, state OPEN/CLOSED/CANCELLED/VOIDED, candidates[], votes{voter→candidate}, winner }
- `offices`: (landId, area, role) → { holder, previousRole, since }

## Code layout
- `core/`: plain Java, no Bukkit: `Election`, `ElectionService`, `Eligibility`, `VettingRules`, `TenureBook`. Unit-tested.
- `LandsPort` interface (members, role of, set role, owner, exists) → `LandsApiPort` (real Lands) and `FakeLands` (tests).
- `bukkit/`: plugin main, `/gov` command, listeners for the Lands events above.

## MVP (this build)
Vetting (playtime + IP), tenure tracking, charters (none/council/republic), one-seat elections on a Lands role in the land's main area, delete, untrust and
owner-change blocks, `/gov` commands. **Later:** property weight (owning a sub-area plot gives +X vote weight or a right to run),
elections inside sub-areas (feudalism: each plot area elects or appoints its own lord), nation-level votes, DiscordSRV vetting.
