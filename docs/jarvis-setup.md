# Nova als privater "Jarvis" – Einrichtung

Ziel: eine **persönliche KI, die nur dir gehört**. Das kluge Gehirn läuft auf
**deinem eigenen Laptop** (Ollama), dein Handy ist die Fernbedienung. Ohne Laptop
antwortet das kleine Offline-Modell auf dem Handy. Deine Daten verlassen nie deine
eigenen Geräte.

## 1. Laptop = das kluge Gehirn (Ollama)

Auf deinem ThinkPad (16 GB RAM reichen gut):

1. **Ollama installieren:** https://ollama.com/download → Windows-Version.
2. **Ein Modell mit gutem Deutsch laden** (einmalig, Terminal/PowerShell):
   ```powershell
   ollama pull qwen2.5:7b
   ```
   (Alternativen: `qwen2.5:14b` = klüger, langsamer; `llama3.1:8b` = solide.)
3. **Im Heimnetz erreichbar machen** – damit das Handy den Laptop findet, muss
   Ollama auf allen Netzwerk-Adressen lauschen. Setze die Umgebungsvariable
   `OLLAMA_HOST=0.0.0.0` und starte Ollama neu:
   - Windows: *Systemsteuerung → System → Erweiterte Systemeinstellungen →
     Umgebungsvariablen* → neue Benutzervariable `OLLAMA_HOST` = `0.0.0.0`.
   - Danach Ollama neu starten (Symbol in der Taskleiste → Quit → neu öffnen).
4. **Laptop-IP herausfinden** (PowerShell): `ipconfig` → die „IPv4-Adresse" im
   WLAN-Abschnitt, z. B. `192.168.1.20`.

## 2. Handy = die Fernbedienung (Nova-App)

In der App: **Einstellungen → Local AI → „Private home brain"**:

- **„Prefer my computer when available"** einschalten.
- **Server address:** `http://<Laptop-IP>:11434` – z. B. `http://192.168.1.20:11434`
- **Model name:** `qwen2.5:7b` (genau der Name aus Schritt 1.2)

Sind Laptop und Handy im **gleichen WLAN** und Ollama läuft, antwortet ab jetzt das
kluge Laptop-Gehirn. Ist der Laptop aus, schaltet Nova automatisch auf das
Offline-Handymodell um.

## 3. Auch von unterwegs zugreifen (optional, privat)

Damit das Handy den Laptop **auch außerhalb des WLANs** erreicht – ohne den
Laptop offen ins Internet zu stellen – nutze ein privates, verschlüsseltes
Netz: **Tailscale** (https://tailscale.com, kostenlos für privat).

1. Tailscale auf Laptop **und** Handy installieren, mit demselben Konto anmelden.
2. In Tailscale bekommt der Laptop eine feste, private Adresse (z. B. `100.x.y.z`).
3. In der App als Server address `http://100.x.y.z:11434` eintragen.

Damit ist Nova von überall erreichbar, aber der Zugang bleibt komplett privat –
nur deine eigenen Geräte sind in diesem Netz.

## 4. „Nur ich"

- **App-Sperre:** *Einstellungen → Local AI → „Lock the app"* verlangt beim
  Öffnen Fingerabdruck/PIN (nutzt die Gerätesicherung).
- Erinnerungen (`assistant-memory.md`) und Health-Daten liegen nur auf deinen
  Geräten, nie in einer fremden Cloud.

## Sicherheits-Hinweis

`OLLAMA_HOST=0.0.0.0` macht Ollama im **lokalen Netz** erreichbar. Stelle den
Port `11434` **nicht** per Portfreigabe ins offene Internet – für Fernzugriff
nimm Tailscale (Schritt 3). So kann wirklich nur dein eigenes Gerät zugreifen.
