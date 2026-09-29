#!/bin/bash
# Runs phrases through Lumi (with Gemma) one by one and records what each engine decided.
# Usage: tools/gemma_batch.sh < tools/gemma_phrases.txt   (OUT=file to change the output file)
ADB="${ADB:-$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
P=io.github.salex27.lumi
OUT="${OUT:-gemma_batch_out.txt}"
: > "$OUT"
# The phrase is shell-escaped with printf %q (apostrophes like "I'm" broke plain quoting).
# Phrases are read from fd 3: adb would otherwise swallow stdin and only the first phrase would run
while IFS= read -r phrase <&3; do
  [ -z "$phrase" ] && continue
  [ "${phrase:0:1}" = "#" ] && continue
  $ADB </dev/null shell am force-stop $P
  $ADB </dev/null logcat -c
  START=$(date +%s)
  MSYS_NO_PATHCONV=1 $ADB </dev/null shell am start -n $P/.presentation.assistant.AssistantActivity --es prompt "$(printf '%q' "$phrase")" >/dev/null
  # Wait for the interpretation and the reply (4 min at most)
  for i in $(seq 1 48); do
    sleep 5
    L=$($ADB </dev/null logcat -d -s LumiInterpret)
    echo "$L" | grep -q "final=\|rules-first" || continue
    # For questions, also wait for the answer
    if echo "$L" | grep -q "ASK/\|final=ASK\|RECALL"; then echo "$L" | grep -q "Answer to" && break; else sleep 20; break; fi
  done
  echo "[$(( $(date +%s) - START )) s] $phrase" >> "$OUT"
  $ADB </dev/null logcat -d -s LumiInterpret | grep "LumiInterpret" | sed 's/^.*LumiInterpret: //' >> "$OUT"
  MSYS_NO_PATHCONV=1 $ADB </dev/null exec-out uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 $ADB </dev/null shell cat /sdcard/ui.xml 2>/dev/null | grep -o 'text="[^"]\{12,\}"' | tail -2 | sed 's/^/    UI: /' >> "$OUT"
  echo "----" >> "$OUT"
done 3<&0
echo END >> "$OUT"
