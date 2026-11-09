#!/usr/bin/env bash
# Prueba de punta a punta en el emulador: descarga, perfil del creador e historial.
set -u
PKG=com.example.markdwn
URL="${URL_PRUEBA:-https://www.tiktok.com/@patrox/video/6742501081818877190}"
OUT=resultados; mkdir -p "$OUT"
UI="python3 .github/pruebas/ui.py"
cap(){ adb exec-out screencap -p > "$OUT/$1.png"; echo "== captura $1"; $UI textos > "$OUT/$1.txt" || true; cat "$OUT/$1.txt"; }

adb logcat -c
adb install -r salida/markdwn-x86_64.apk || exit 1

echo "== 1. Abrir la app con un enlace compartido"
adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$URL' -n $PKG/.MainActivity"
echo "Estado: $($UI esperar tvEstado 240 'Listo|No se pudo')"
cap 01_inicio

echo "== 2. Descargar"
$UI tocar btnDescargar
R=$($UI esperar tvEstado 300 'Guardado|Error|No se|privad|no ofreci|Solo se')
echo "Resultado descarga: $R"
sleep 3; cap 02_descarga

echo "== 3. Archivo en la galería"
adb shell ls -la /sdcard/Movies/markdwn/ | tee "$OUT/archivos.txt"
F=$(adb shell ls /sdcard/Movies/markdwn/ | tr -d '\r' | grep mp4 | head -1)
[ -n "$F" ] && adb pull "/sdcard/Movies/markdwn/$F" "$OUT/video_app.mp4"

echo "== 4. Perfil del creador"
$UI tocar btnResPerfil
R2=$($UI esperar estado 240 'videos recientes|No se|Error|privad|no tiene')
echo "Resultado perfil: $R2"
sleep 8; cap 03_perfil

echo "== 5. Descargar un video desde el perfil"
$UI tocar miniatura
R3=$($UI esperar estado 240 'Guardado|Error|No se')
echo "Resultado descarga desde perfil: $R3"
sleep 2; cap 04_perfil_descarga

echo "== 6. Mis descargas"
adb shell input keyevent KEYCODE_BACK; sleep 2
$UI tocar nav_historial || $UI tocar_texto "Mis descargas"
sleep 4; cap 05_historial

echo "== 7. Errores graves en el registro"
adb logcat -d > "$OUT/logcat.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime" -A 25 "$OUT/logcat.txt" | head -80 | tee "$OUT/fallos.txt"
{ echo "descarga=$R"; echo "perfil=$R2"; echo "descarga_perfil=$R3"; } > "$OUT/resumen.txt"
cat "$OUT/resumen.txt"
exit 0
