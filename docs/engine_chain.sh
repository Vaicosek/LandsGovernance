#!/bin/bash
T="C:/AI Brain/mods/gamedev-kit/tools/astra_task.sh"
D="C:/work/MInecraft Server/LandsGovernance"
echo "### realms $(date +%T)"; bash "$T" "$D" "$D/docs/astra-realms-brief.md" 3600 | head -3
echo "### fixes $(date +%T)"; bash "$T" "$D" "$D/docs/astra-estate-fixes-brief.md" 1800 | head -3
echo "### CHAIN DONE $(date +%T)"
