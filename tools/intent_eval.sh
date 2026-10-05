#!/bin/bash
# Scores the top-level intent routing (#1) of the engine active on a device against the evaluation set.
# Each sentence runs through the real assistant (like gemma_batch.sh); the label comes from the "route=" log line
# (rules) or the interpreted action (LLM / rules).
# Usage: tools/intent_eval.sh [app/src/test/resources/intent_eval.tsv]   (OUT=file for the details)
ADB="${ADB:-$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
P=io.github.salex27.lumi
SET="${1:-app/src/test/resources/intent_eval.tsv}"
OUT="${OUT:-intent_eval_out.txt}"
: > "$OUT"
ok=0; total=0

label_of() {
  # $1 = the LumiInterpret log of one sentence
  local route action
  route=$(echo "$1" | grep -o 'route=[A-Z]*' | head -1 | cut -d= -f2)
  if [ -n "$route" ]; then echo "$route"; return; fi
  action=$(echo "$1" | grep -o 'final=[A-Z_]*\|rules-first · [A-Z_]*' | head -1 | sed 's/.*[= ]//')
  case "$action" in
    CREATE|CREATE_MANY|UPDATE_STATUS|RESCHEDULE|SET_PRIORITY|EDIT|PLAN_DAY|DELETE) echo TASK ;;
    ASK|RECALL|WEATHER|SUMMARIZE|DAY_BRIEF|REMEMBER) echo QUESTION ;;
    DEVICE|SMART_ALARM|ROUTINE|NAVIGATE) echo DEVICE ;;
    *) echo "?${action}" ;;
  esac
}

while IFS=$'\t' read -r expected phrase <&3; do
  [ -z "$expected" ] && continue
  [ "${expected:0:1}" = "#" ] && continue
  $ADB </dev/null shell am force-stop $P
  $ADB </dev/null logcat -c
  MSYS_NO_PATHCONV=1 $ADB </dev/null shell am start -n $P/.presentation.assistant.AssistantActivity --es prompt "$(printf '%q' "$phrase")" >/dev/null
  for i in $(seq 1 48); do
    sleep 5
    L=$($ADB </dev/null logcat -d -s LumiInterpret)
    echo "$L" | grep -q "route=\|final=\|rules-first" && break
  done
  got=$(label_of "$L")
  total=$((total + 1))
  [ "$got" = "$expected" ] && ok=$((ok + 1)) || echo "MISS expected=$expected got=$got «$phrase»" >> "$OUT"
done 3< "$SET"
echo "$ok / $total correct" | tee -a "$OUT"
