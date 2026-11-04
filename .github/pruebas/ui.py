"""Ayudas para manejar la app en el emulador con uiautomator (sin dependencias)."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.example.markdwn"

def adb(*a, check=True):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check).stdout

def dump():
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        x = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if "<hierarchy" in x:
            return ET.fromstring(x[x.index("<hierarchy"):])
        time.sleep(1)
    return None

def nodos():
    r = dump()
    return [] if r is None else list(r.iter("node"))

def buscar(id_=None, texto=None):
    for n in nodos():
        if id_ and n.get("resource-id") == f"{PKG}:id/{id_}":
            return n
        if texto and texto.lower() in (n.get("text") or "").lower():
            return n
    return None

def tocar(id_=None, texto=None):
    n = buscar(id_, texto)
    if n is None:
        print(f"NO ENCONTRADO: {id_ or texto}"); return False
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True

def texto(id_):
    n = buscar(id_)
    return None if n is None else n.get("text")

def esperar(id_, patrones, segundos):
    fin = time.time() + segundos
    ultimo = None
    while time.time() < fin:
        t = texto(id_)
        if t:
            ultimo = t
            for p in patrones:
                if p.lower() in t.lower():
                    return t
        time.sleep(3)
    return ultimo

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tocar":
        sys.exit(0 if tocar(id_=sys.argv[2]) else 1)
    if cmd == "tocar_texto":
        sys.exit(0 if tocar(texto=sys.argv[2]) else 1)
    if cmd == "esperar":
        r = esperar(sys.argv[2], sys.argv[4].split("|"), int(sys.argv[3]))
        print(r)
    if cmd == "textos":
        for n in nodos():
            if n.get("text"):
                print(f'{n.get("resource-id")}: {n.get("text")}')
