# Cities v0.2

Standalone chunk claims with roles, lots, elections and charters. Requires Paper 26.3 and Java 25.
Rules: [DESIGN.md](DESIGN.md).

| Command | Effect |
|---|---|
| `/c` | Current city, clickable realm, owner, role, lot and election status |
| `/c realm`, `/c realm info <realm>`, `/c realm list` | Ruler, capital, city spawn buttons, tax, relations and ballot; treasury requires REALM_TREASURY |
| `/c realm create <name>` | City owner creates a realm; cost comes from city treasury |
| `/c realm invite <city>`, `kick <city>`, `leave` | One-day invitation with Accept/Decline buttons; capital cannot leave |
| `/c realm capital <city>`, `delete` | Ruler moves capital or dissolves realm; treasury returns to capital |
| `/c realm role <player> <role>` | REALM_ROLES assigns a lower realm role to a member |
| `/c realm charter sign elected`, `charter revoke`, `run`, `vote` | Elected ruler, notice-period revocation and clickable ballot |
| `/c realm treasury`, `deposit <amount>`, `withdraw <amount>`, `tax <amount>` | Realm ledger; daily city tax after member tax/upkeep, capital exempt |
| `/c realm ally <realm>`, `truce <realm>`, `enemy <realm>`, `neutral <realm>` | Ally/truce require consent; enemy is delayed; neutral immediate |
| `/c realm chat`, `/c rc <message>` | Toggle realm chat or send one message |
| `/c realm setspawn`, `spawn [realm]`, `flag public on/off`, `fee <amount>` | Shared warm-up; members/allies free, visitors pay on arrival, enemies refused |
| `/c create <name>` | Create and claim this chunk; one owned city per player |
| `/c claim`, `/c unclaim` | Add an adjacent chunk or remove this chunk (CLAIM); keep at least one |
| `/c invite <player>`, `/c join <city>` | Invite for 24 hours (INVITE), accept as the lowest role |
| `/c kick <player>` | Remove a lower-ranked member (KICK) |
| `/c role <player> <role>` | Change a lower-ranked member to a role below yours (SET_ROLES) |
| `/c estate create <name> <owner>` | PLOTS creates an estate on a free city chunk; owner must be a member |
| `/c estate claim`, `/c estate unclaim`, `/c estate delete <name>` | Add touching city chunks, return chunks, or dissolve and pay the owner; charters protect surrender |
| `/c estate give <member>`, `/c estate trust <player>`, `/c estate untrust <player>` | Owner transfers ownership or manages builders/container access |
| `/c estate setspawn`, `/c estate flag public <on/off>` | Owner sets spawn or welcomes visitors even in a private city |
| `/c spawn <estate>`, `/c spawn <city> <estate>` | Visit after warm-up; city members travel free |
| `/c fee <amount>`, `/c estate fee <amount>` | SETTINGS / estate owner sets visitor fee, capped by economy.max-spawn-fee (100) |
| `/c estate treasury`, `/c estate deposit <amount>`, `/c estate withdraw <amount>` | Anyone deposits; only owner views the balance/ledger or withdraws |
| `/c lot give <player>`, `/c lot revoke` | Assign a chunk lot (PLOTS); lot owners may revoke their own |
| `/c leave`, `/c delete`, `/c owner <player>` | Leave; owner deletes or hands over to a member |
| `/c vet` | Vet your account for elections |
| `/c elect <role> [hours]` | Owner opens an election (default 48 hours) |
| `/c run [role]`, `/c vote [candidate]` | Stand or open the clickable ballot |
| `/c cancel` | Owner cancels elections unless a charter forbids it |
| `/c charter`, `/c charter sign council`, `/c charter sign republic`, `/c charter revoke` | View or change the charter |
| `/c admin vet <player>`, `/c admin unvet <player>` | Staff override (`cities.admin`) |
| `/c admin close <city>` | Staff count votes now; `end` is an alias |
| `/c setspawn`, `/c spawn [city]` | SETTINGS sets spawn inside the city; members or public visitors teleport after standing still |
| `/c map` | North-up 9 × 17 map; click adjacent wilderness to claim within 8 chunks (CLAIM) |
| `/c flag`, `/c flag <name> <on/off>` | Clickable city flags; SETTINGS changes them |
| `/c deposit <amount>`, `/c withdraw <amount>`, `/c treasury` | Members deposit; BANK withdraws; balance and last 10 ledger entries |
| `/c tax <amount>`, `/c upkeep` | SETTINGS sets daily member tax; view upkeep and unpaid days |
| `/c ban <player>`, `/c unban <player>` | BAN, rank and election guard; bans block entry |
| `/c chat [message]` | Toggle members-only chat or send one message |
| `/c show` | Show nearby claim borders for 10 seconds |
| `/c list [page]`, `/c info <name>` | Browse cities by member count, visitor flags and public spawn buttons |
| `/c admin bypass`, `/c admin delete <city>` | Staff protection bypass; delete and void open elections, logged |

Aliases: `/city`, `/cities`. Cities and estates share a case-insensitive name space. Estates use the parent city’s chunks, not an extra city slot. Estate balances are paid to their owners on dissolution, departure or city deletion; failed payouts retain the affected estate. Fees are ignored without an economy provider.

Membership and governance commands use the city you stand in if you belong to it, otherwise your only city.
Claim and lot commands use your current chunk. Role order and permissions come from `config.yml`.
Owner and Count have all permissions; Admin lacks SET_ROLES and BANK. Citizen and Member are unchanged.
New cities start with the creator's location as spawn; existing cities use `/c setspawn`.

Treasury, tax and upkeep require Vault plus an economy provider. Without them, economy commands explain what is missing
and daily charges are skipped. At `economy.day-hour` (server time), taxes enter the treasury before upkeep leaves it.
Three unpaid upkeep days are grace; each later unpaid day removes the newest non-spawn, non-estate chunk, keeping the last chunk.
Paying in full resets the streak. The treasury warns when it cannot cover tomorrow. Tax failures are listed for the owner
and member in `/c`; nobody is auto-kicked. Ledger history keeps 50 entries.
The last processed date is saved; a restart processes at most one missed day, without charging all offline days.
A first installation starts its daily schedule at the next configured hour. Online members receive notices;
offline members see tax strikes through `/c`. Bypass and chat toggles reset on logout.

| Rule | Default |
|---|---|
| Chunk limit | 8 + 4 per member; new chunks touch the city |
| Lot protection | Only the lot owner builds or uses containers; INTERACT follows roles |
| Voting | Vetted, member, voting role held for 7 days |
| Vetting | 10 hours played; one vetted account per salted IP hash |
| Winner | Most votes, then longest tenure; fewer than 3 votes changes nothing |
| Open election | Blocks deletion, ownership transfer and kicking eligible voters/candidates |
| Council | No cancellation; offices re-elected every 30 days |
| Republic | Council rules plus owner elections |
| Signed charter | Only lot owners may revoke their lots; leaving the charter takes 14 days |

Build with Java 25: `./gradlew build`. Jar: `build/libs/cities-0.2.0.jar`.
Data is saved in `plugins/Cities/data.json`. For an existing installation, move the old plugin data folder to `plugins/Cities` while the server is stopped before enabling the renamed jar.

Realm defaults: elected terms 14 days, city join tenure 7 days, unpaid realm tax kicks after 3 days,
truce lock 3 days, enemy delay 24 hours. Open realm elections block city kicks, capital moves, realm deletion,
charter changes and role assignments; daily withdrawals are capped. Staff with `cities.admin` can bypass these blocks,
with logged actor/action. Realm ties use earlier candidacy; city elections retain their tenure tie-break.
Capital deletion selects the largest remaining city (name breaks size ties); the last capital owner receives the realm balance.
Failed payouts retain the city/realm; failed teleport refunds are persisted and retried. Creation costs remain configured economy sinks.
Phase 2 awaits compile and server verification.

## Solo test

1. Put this jar in a Paper 26.3 server's `plugins/`, start once, then stop.
2. Copy [config-solo-test.yml](test-server/config-solo-test.yml) over `plugins/Cities/config.yml` and restart.
   It removes tenure/playtime/IP restrictions and needs one voter. Use a private offline-mode test server for alts.
3. Owner: `/c create Testcity`, `/c invite Alt`. Alt: `/c join Testcity`.
4. Each account: `/c vet`. Owner: `/c elect Count 1`. Alt: `/c run`, `/c vote Alt`.
5. Staff: `/c admin close Testcity`. Alt is now Count and can claim an adjacent chunk with `/c claim`.
6. Give Alt a lot with `/c lot give Alt`; check that even the owner cannot build there.
   Sign council, then check that only Alt can revoke it.

Limits: chunk-sized lots; no sub-area elections or property-weighted voting yet. Announcements reach online members;
offline members see open elections through `/c`.

## Install on a server

Cities runs inside the Minecraft server. Build on a machine with JDK 25 and
Bash, then upload the jar to the Minecraft panel; the AbexTech Python container
is not the plugin build or Minecraft host. Include the Gradle wrapper JAR and
scripts with the source. Gradle downloads its distribution/dependencies on the
first build. No administrator access or host service setup is required.

From an existing source checkout, pass an existing writable output directory:

~~~sh
bash install.sh /path/to/upload-directory
~~~

install.sh only runs the Gradle build and copies build/libs/cities-0.2.0.jar to
that directory. It does not clone/update source, create .env or start/stop servers.
You may pass the Minecraft plugins directory only after stopping Minecraft and
backing up its data. The plugin does not read .env; settings belong in
plugins/Cities/config.yml.

Run Paper 26.3 with Java 25. Install Vault and an economy provider for economy
functions. Stop Minecraft through its panel, back up plugins/Cities and the old
jar, remove the old Cities jar from plugins/ to avoid duplicate versions, and
upload the built jar there using Files/SFTP. Start once to generate
plugins/Cities/config.yml, stop, edit that host-only file, then start again.
Preserve plugins/Cities/data.json and other plugin state. Do not use live reload.

### Restocker shop feed

Configure these keys in the server's plugins/Cities/config.yml, never in the
repository's src/main/resources/config.yml with live credentials:

~~~yaml
feed:
  url: "https://shop.example.invalid/api/cities/sale"
  web_base: "https://shop.example.invalid"
  market-id: "example-market"
  key: ""
  server-id: "example-server"
~~~

- feed.url: full Restocker sale endpoint, ending in /api/cities/sale. The plugin
  derives /api/cities/webview from it for shop snapshots.
- feed.web_base: public HTTPS base URL, without /shops or an API suffix; used for
  player shop links.
- feed.market-id: the Restocker market identifier registered for this server.
- feed.key: the private shared signing key registered for that market in
  Restocker's Cities integration (cities_keys). Obtain/register it privately
  through the Restocker administrator; this is not the Discord bot token.
- feed.server-id: stable identifier for this Minecraft server in feed events.

Leaving url, market-id or key empty disables delivery. Shop web links also need
web_base. On Restocker, EARTHMC_INGEST=0 captures Cities sales in shadow mode;
set it to 1 only when ready for real booking. Keep both hosts' clocks synchronized
for signed requests. Test a sale and the in-game shop web link after configuration.

### Updating

Update the source checkout on the build host through its repository tooling or
SFTP upload, rerun install.sh with the output directory, then stop Minecraft,
back up plugin data and replace the jar through Files/SFTP as above. Restart and
check Console, a Cities command, a test sale and its shop link. The Minecraft
panel only needs the built jar and persistent plugin configuration/data.
