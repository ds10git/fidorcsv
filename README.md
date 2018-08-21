hibiscus.sync.fidorcsv
======================

Ein Plugin zum halbautomatischen Abruf der Umsätze bei der Fidor Bank mit Hibiscus.


## Installation

- Öffnen Sie die Installation von Plugins in Jameica über das Menü `Datei->Plugins online suchen...`
- Klicken Sie dann auf `Repositories bearbeiten`
- Im sich öffnenden Dialog _Plugin-Repositories_ dann auf `Neues Repository hinzufügen...`
- Als URL für das neue Repository geben Sie dann Folgendes ein: 

>http://hibiscus.tvbrowser.org/

Nachdem das Repository hinzugefügt wurde, wählen Sie im Tab `Verfügbar Plugins`
das hinzugefügte Repository aus.

Das Plugin _Fidor-CSV_ wird angezeigt und kann über den Klick auf den Knopf `Installieren...` installiert werden.

Nach einem Neustart von Jameica sollte für Konten bei der Fidor Bank als Zugangsweg _Fidor-CSV_ automatisch eingestellt sein, so dass die Synchronisierung direkt gestartet werden kann.


## Konto anlegen

Wenn Sie ein neues Konto bei der Fidor Bank in Hibiscus anlegen möchten, klicken Sie auf `Konten` und dort dann auf `Konto manuell anlegen`.

Tragen Sie im Tab _Eigenschaften_ den Kontoinhaber ein, im Tab _Zugangsdaten_ geben Sie die IBAN ein, wählen als `Zugangsweg` _Fidor-CSV_ aus. Das Feld `Kundenkennung` muss ausgefüllt werden, kann aber ein beliebiger Eintrag sein, da die Kundenkennung vom Plugin _Fidor-CSV_ nicht benötigt wird. 