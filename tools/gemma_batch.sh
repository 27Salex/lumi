#!/bin/bash
# Pasa frases por Lumi (con Gemma) una a una y guarda lo que decide cada motor.
ADB=~/AppData/Local/Android/Sdk/platform-tools/adb.exe
P=com.antigravity.gemininanotaskmanager
OUT="${OUT:-gemma_batch_out.txt}"
: > "$OUT"
while IFS= read -r phrase <&3; do
  [ -z "$phrase" ] && continue
  $ADB </dev/null shell am force-stop $P
  $ADB </dev/null logcat -c
  MSYS_NO_PATHCONV=1 $ADB </dev/null shell am start -n $P/.presentation.assistant.AssistantActivity --es prompt "'$phrase'" >/dev/null
  # Espera a la interpretación y a la respuesta (o 4 min como mucho)
  for i in $(seq 1 48); do
    sleep 5
    L=$($ADB </dev/null logcat -d -s LumiInterpret)
    echo "$L" | grep -q "final=\|reglas directas" || continue
    # Si es pregunta, espera también a la respuesta
    if echo "$L" | grep -q "ASK/\|final=ASK\|RECALL"; then echo "$L" | grep -q "Respuesta a" && break; else sleep 20; break; fi
  done
  $ADB </dev/null logcat -d -s LumiInterpret | grep "LumiInterpret" | sed 's/^.*LumiInterpret: //' >> "$OUT"
  MSYS_NO_PATHCONV=1 $ADB </dev/null exec-out uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 $ADB </dev/null shell cat /sdcard/ui.xml 2>/dev/null | grep -o 'text="[^"]\{12,\}"' | tail -2 | sed 's/^/    UI: /' >> "$OUT"
  echo "----" >> "$OUT"
done 3<&0
echo FIN >> "$OUT"
