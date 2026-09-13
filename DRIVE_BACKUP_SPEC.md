# Számlakezelő – Google Drive biztonsági mentés

## Cél
- Minden felhasználó a saját Google Drive-jába ment.
- Az app a `Számlakezelő` nevű mappát használja/létrehozza.
- A helyi adatok maradnak az elsődleges adatok; a Drive biztonsági mentés.
- Mentés automatikusan történik adatváltozás után, rövid késleltetéssel, hogy több gyors módosítás ne indítson felesleges feltöltéseket.
- Kézi `Mentés most` és `Visszaállítás Drive-ról` funkció is lesz.

## Google API
- Google Drive REST API v3
- OAuth 2.0 Android kliens
- Scope: `https://www.googleapis.com/auth/drive.file`
- Nem kérünk teljes Drive-hozzáférést.

## Backup formátum
Fájl: `szamlakezelo_backup.json`

Tartalom:
```json
{
  "app": "Számlakezelő",
  "schemaVersion": 1,
  "savedAt": "ISO-8601 timestamp",
  "trialStartedAt": "ISO-8601 timestamp",
  "data": []
}
```

## Konfliktuskezelés
- Helyi és Drive mentés is tartalmaz időbélyeget.
- Automatikus visszaállítás nem írhat felül frissebb helyi adatot kérdés nélkül.
- Visszaállítás előtt helyi biztonsági pillanatkép készül.
- A próbaidő kezdete is bekerül a mentésbe; visszaállításkor a korábbi kezdőidő marad érvényes.

## Play modell
- Ingyenes letöltés a Google Play Áruházból.
- 30 napos teljes funkcionalitású próbaidő.
- A próbaidő után egyszeri, nem fogyó vásárlás oldja fel végleg az appot.
- Nincs előfizetés és nincs automatikus megújítás.
- A vásárlás után az app korlátlan ideig használható az adott Google Play-fiókkal.
- A próbaidő lejárta nem töröl adatot; az adatok megmaradnak.

## Android azonosító
- App név: `Számlakezelő`
- Application ID: `hu.rauch.szamlakezelo`
