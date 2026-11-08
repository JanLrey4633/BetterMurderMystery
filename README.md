# BetterMurderMystery

Ein leicht einzurichtendes Murder-Mystery-Plugin fuer **Paper 1.21.x** (Java 21).

## Installation

1. Java 21 und Maven installieren.
2. Im Projektordner `mvn package` ausfuehren.
3. `target/BetterMurderMystery.jar` in den Serverordner `plugins/` kopieren.
4. Paper starten oder neu starten.

## Arena einrichten

Alle Setup-Befehle als OP oder mit `bettermurdermystery.admin` ausfuehren:

| Befehl | Funktion |
|---|---|
| `/mm setlobby` | Setzt den Warte-Lobby-Spawn |
| `/mm setspawn` | Fuegt an deiner Position einen Arena-Spieler-Spawn hinzu |
| `/mm setcoinspawn` | Fuegt einen Coin-Spawnpunkt hinzu |
| `/mm clearcoinspawns` | Entfernt alle Coin-Spawnpunkte |
| `/mm setminplayers 4` | Setzt die Mindestspielerzahl (nie unter 4) |
| `/mm info` | Zeigt den Setup- und Rundenstatus |
| `/mm reload` | Laedt die Konfiguration neu |
| `/mm start` | Startet die Runde manuell |
| `/mm stop` | Beendet die Runde |

Spieler treten mit `/mm join` bei und verlassen mit `/mm leave`. Spieler verwenden alternativ `/mm join` und `/mm leave`. Sobald genug Spieler in der Warteschlange sind, startet der Countdown automatisch.

## Inventar-GUIs\n\nAdmins öffnen mit `/adminmmgui` direkt das Admin-Menü. Spieler können dort beitreten, verlassen und Spielinfos ansehen. Admins erhalten zusätzlich ein Admin-Menü mit Start/Stop, Lobby- und Arena-Spawn-Verwaltung, Coin-Spawns, Config-Reload und einem Einstellungsmenü. Im Einstellungsmenü lassen sich Mindestspielerzahl, Countdown, Coin-Intervall und maximale Coin-Anzahl per Links-/Rechtsklick ändern. Änderungen werden direkt in der `config.yml` gespeichert.\n\n## Spielablauf

- Pro Runde gibt es genau einen Murderer und einen Sheriff. Alle uebrigen Spieler sind Innocents.
- Der Murderer erhaelt ein markiertes Eisenschwert.
- Der Sheriff erhaelt einen Bogen und einen Pfeil.
- Nur der Sheriff kann den Murderer mit einem Bogenschuss ausscheiden lassen. Der Murderer kann Teilnehmer mit seinem markierten Spielschwert ausscheiden lassen.
- Goldnuggets an den konfigurierten Coin-Punkten sind Rundencoins. Der aktuelle Stand wird beim Einsammeln angezeigt.
- Die Innocents gewinnen, wenn der Murderer ausscheidet. Der Murderer gewinnt, wenn alle anderen ausgeschieden sind.

## Inventar-Schutz

Vor Rundenbeginn werden Inventar, Ruestung, Nebenhand, Enderchest, Erfahrung, Spielmodus und Position im Speicher gesichert. Das Spielinventar wird fuer die Runde geleert. Nach Rundenende werden die gesicherten Daten wiederhergestellt. Bei einem normalen Verlassen waehrend der Runde wird das Inventar ebenfalls direkt wiederhergestellt.

**Hinweis:** Die Sicherung liegt waehrend der laufenden Runde im Arbeitsspeicher. Bei einem unerwarteten Serverabsturz kann eine laufende Inventarsicherung daher nicht garantiert wiederhergestellt werden. Vor dem ersten Einsatz auf dem Live-Server bitte auf einem Testserver pruefen.

## Konfiguration

Nach dem ersten Start wird `plugins/BetterMurderMystery/config.yml` erstellt. Dort kannst du Mindestspielerzahl, Countdown, Coin-Spawn-Intervall und maximale gleichzeitig liegende Coins einstellen. Arena- und Coin-Punkte werden bequem ingame gesetzt und automatisch gespeichert.
