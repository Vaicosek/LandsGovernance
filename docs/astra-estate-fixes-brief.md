# Job: Cities, 3 estate fixes (run after the realms job; build on its code)

Work dir: `C:\work\MInecraft Server\LandsGovernance`. You can't run Gradle; Claude compiles and tests after you. Never edit existing
tests; add new tests in a new file `EstateFixesTest.java`. Each test must FAIL before its fix (write it against the old
behaviour, reason it through). Our player words: city, lot, estate, realm, treasury.

1. `LandBook.can()` returns for estate owner / trusted / lot owner before the ban check, so a banned player on an
   estate's trusted list can still build. The ban must win in every path. Also, `ban` must remove the player from all
   estate trusted lists of that city.
2. `dissolve()` returns false when an estate has a balance and there is no economy provider, so the estate (and its city)
   can't be deleted or left. With no economy: drop the balance (log it), and proceed.
3. `dissolve()` uses `System.currentTimeMillis()` for the ledger entry; use the `now` passed in, like the rest of the code.

Report: files changed, tests added, what you could not check.
