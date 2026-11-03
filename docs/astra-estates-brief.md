# Job: LandsGovernance phase 1b: estates (named places inside a land with own spawn, bank and TP fee)

Work dir: `C:\work\MInecraft Server\LandsGovernance`. Phase 1 (spawn, bank, flags, upkeep, tax...) is already in the code: read it
first and build on it (same `Spot`, `EconomyPort`, ledger, warm-up). Same rules as before: core tests first in a new test
file, never edit existing tests, `Result.ok/no`, one `/land` command, clickable chat, no typed IDs. You can't run Gradle;
Claude compiles and tests after you.

## Why (John 2026-11-03)
In Lands a shop inside a city ("greyhames" inside "kingslanding") can't have its own `/la spawn greyhames` unless it's its
own claim, and then it takes a claim slot and isn't part of the city. We want: part of the main land, no extra slot,
but its own spawn and its own balance (TP fees are a thing).

## Rules
- `Estate` data: `name` (3-24 `[A-Za-z0-9_]`), `owner` (UUID, must be a member of the land), `chunks` (keys; each also
  in the parent land), `spawn` (`Spot`), `balance`, `ledger`, `spawnFee`, `trusted` (Set<UUID>), `created`. Stored on
  `Land.estates` (map by lower-case name).
- **Names are unique across all lands and all estates** (case-insensitive), so `/land spawn greyhames` resolves directly.
  Creating a land with a name an estate already uses is refused too.
- Create: `/land estate create <name> <owner>` while standing in a chunk of your land. Needs PLOTS. The chunk becomes
  the estate's first chunk and the owner's plot (estate chunks are plots of the estate owner); spawn = where you stand.
  `/land estate claim` / `unclaim`: the estate owner, or PLOTS holders, add or remove a chunk of the same land that touches
  the estate and isn't another plot/estate. Never remove the last chunk; `/land estate delete <name>` for that.
  Estate chunks count toward the parent land's chunk limit only. A player can own estates whether or not they own a land.
- Protection: in estate chunks only the estate owner and `trusted` may BUILD and use CONTAINERS (`/land estate trust
  <player>` / `untrust`, estate owner only). INTERACT follows the land's roles and visitor flags as for plots.
- Under a signed charter (council/republic) only the estate owner can delete the estate or give up chunks (same as
  plots). Transferring: `/land estate give <member>` by the estate owner only.
- Spawn: `/land estate setspawn` (estate owner, standing inside it). `/land spawn <estate>` and `/land spawn <land>
  <estate>` both work. Access: members of the parent land always; others if the parent land is public **or** the estate
  flag `public` is on (so a shop can be public inside a private city).
- TP fee: lands and estates each have `spawnFee` (`/land fee <amount>` with SETTINGS for the land, `/land estate fee
  <amount>` by the estate owner; max config `economy.max-spawn-fee`). A visitor pays it on arrival (after the warm-up, only
  if the teleport happens) into the bank of the place they go to; members of the parent land pay nothing. Not enough money
  -> refused, nothing charged. No economy -> fees ignored.
- Bank: `/land estate bank`, `/land estate deposit <amount>` (anyone), `/land estate withdraw <amount>` (estate owner),
  same ledger as the land bank.
- Owner leaves or is kicked from the land (through the election guard): their estates go back to the land as plain chunks;
  the estate balance is paid to the leaving owner (it's their money). Land deleted: estate balances paid to their owners.
- Show it: `/land info <land>` lists estates with owner and a [Spawn] button (with the fee); the map colours estate chunks
  like plots (own = aqua) and hover shows the estate name; entering one shows "<estate> (<land>)" in the action bar;
  `/land` standing in one shows its owner, fee and balance (balance only to its owner).
- Admin delete of a land also handles estates as above.

Update README (short table rows), ROADMAP (add an "Estates" row as done, with a note that this is the claim-in-a-claim),
config keys. Done = new core tests for: unique names across lands/estates, estate inside parent and no extra land slot,
chunk limit shared with the parent, build only by estate owner/trusted, spawn access (private city + public estate), fee
charged once into the estate bank and not for members, refused when broke, owner leaving returns chunks and money,
charter lock. All old tests still pass. End with a short report.

## Also in this job: our own names (John: "call everything differently ... so it's not ripping them off", "easy and short like /c spawn")
Rename every player-facing word; Java package names may stay. Do the rename first, then build estates with the new words.
| Old | New |
|---|---|
| plugin name `LandsGovernance` (plugin.yml `name`, jar `archives`/`rootProject.name`, data folder) | `Cities` (jar `cities-<version>.jar`) |
| command `/land` | `/c`, aliases `city`, `cities` |
| land (in every message, help, README, config comments) | city |
| plot | lot (`/c lot give <player>`, `/c lot revoke`) |
| bank | treasury (`/c treasury`, deposit/withdraw stay) |
| nation (future) | realm |
| estate | estate (new word, keep) |
| `gov.admin` permission | `cities.admin` |
All the clickable buttons, tab-complete, usage lines and action-bar texts must use the new command. Grep for "/land",
"Land ", "land", "plot" in messages afterwards so none is left. Rename `test-server/config-solo-test.yml` keys only if a key
name contains "land" or "plot". Update DESIGN.md/README.md/ROADMAP.md wording to match (keep the history sections as they are).
