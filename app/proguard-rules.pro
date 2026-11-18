# Eigene Klassen behalten (klein, erleichtert Fehlersuche)
-keep class ch.jachermann.shieldlauncher.** { *; }
# Warnungen zu optionalen Abhängigkeiten von OkHttp/Coil ignorieren
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
