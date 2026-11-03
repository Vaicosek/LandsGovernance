# Job: Cities, 3 chat text fixes (found in the live bot check)

Work dir: `C:\work\MInecraft Server\LandsGovernance`. You can't run Gradle; Claude compiles and tests after you. Never edit
existing tests. Text only: no change to commands, permissions or economy. Our player words: city, lot, estate, realm, treasury.

1. "<city> has no realm." is sent twice by `/c` (status) and also shows in `/c info`. Sent from
   `RealmCommands.java:28`; find every caller in `Phase1.java` / `GovCommand.java`. `/c` shows it once; `/c info` doesn't show it
   when the city has no realm (still show the realm button when it has one).
2. `/c list` header (`Phase1.java:347`) reads "Cities Cities 1/1" because the chat prefix `Msg.PREFIX` is already "Cities ".
   Make it read `Cities (page 1/1)` once: the prefix, then "(page N/M)".
3. `Phase1.java:351` says "1 members": use "1 member" for one, "N members" otherwise.

Report: files changed, and what you could not check.
