# Shield Launcher

Eigener, werbefreier Startbildschirm für die Nvidia Shield TV mit Plex-Integration.

## Aktueller Stand

Version 0.1 ist eine **Test-App**. Sie prüft, ob sich ein bestimmter Film direkt in der Plex-App öffnen lässt.

## Installation auf der Shield

Jeder Push auf `main` baut automatisch eine APK und veröffentlicht sie als Release `latest`:

`https://github.com/JAchermann98/shield-launcher/releases/download/latest/shield-launcher.apk`

Auf der Shield mit der App **Downloader** diese Adresse öffnen und installieren.

## Signatur

Die APK wird mit einem eigenen Schlüssel signiert, damit Updates ohne Neuinstallation möglich sind.
Dafür braucht das Repository die Secrets `KEYSTORE_BASE64` und `KEYSTORE_PASSWORD`.
Ohne diese Secrets wird mit einem wechselnden Debug-Schlüssel signiert.
