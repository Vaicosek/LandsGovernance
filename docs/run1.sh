#!/bin/bash
# Drive the election test through the server console, acting as the logged-in players.
S="/c/work/MInecraft Server/tools/gov-test-server"
F=$S/cmds3.txt
say() { echo "$1" >> "$F"; timeout 0.6 tail -f /dev/null; }
as() { say "c admin as $1 ${*:2}"; }
O=Vaicos; A=Bcen_Isman; B=War_Isman; C=NotNormalNormal; D=Vaicos_Isman; E=Boris_Isman

say "say [test] 1. create city + members"
for p in $A $B $C $D $E; do say "tp $p $O"; done
as $O create Testland
for p in $A $B $C $D $E; do as $O invite $p; as $p join Testland; done
for p in $O $A $B $C $D $E; do as $p vet; done
as $O role $A Citizen
as $O role $B Citizen

say "say [test] 2. election for Count"
as $O elect Count 1
as $A run
as $B run
as $C vote $A
as $D vote $A
as $E vote $A
as $O vote $B

say "say [test] 3. abuse during election (all must be refused)"
as $O delete Testland
as $O kick $C
as $O owner $C

say "say [test] 4. close"
say "c admin close Testland"

say "say [test] 5. new Count claims the next chunk and gives a lot"
say "execute as $A at $O run tp @s ~16 ~ ~"
as $A claim
as $A lot give $E

say "say [test] 6. council charter: owner can't cancel or take the lot"
as $O charter sign council
as $O elect Admin 1
as $O cancel
say "execute as $O at $A run tp @s ~ ~ ~"
as $O lot revoke
say "say [test] done"

say "say [test] 7. estate Greyhames inside Testland (own spawn, fee, treasury)"
say "execute as $O at $O run tp @s ~-16 ~ ~"
as $O estate create Greyhames $B
as $O estate create Greyhames $C
as $B estate setspawn
as $B estate fee 5
as $B estate flag public on
as $B estate trust $E
as $B estate deposit 0
as $B estate treasury
as $O fee 2
as $O estate create Testland $B
say "say [test] 7b. refused: non-owner changes, duplicate name, last chunk"
as $C estate fee 1
as $C estate trust $C
as $B estate unclaim
as $B estate give $O

say "say [test] 8. spawn: members travel free (all 6 accounts are members, so the visitor fee needs a 7th account)"
as $A spawn Greyhames
as $B spawn Testland Greyhames
as $A map
as $O info Testland

say "say [test] 9. estate back to the city; treasury is paid to the owner"
as $B estate delete Greyhames
as $O list
say "say [test] done 7-9"
