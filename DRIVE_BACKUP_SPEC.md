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
  "data": []
}
```

## Konfliktuskezelés
- Helyi és Drive mentés is tartalmaz időbélyeget.
- Automatikus visszaállítás nem írhat felül frissebb helyi adatot kérdés nélkül.
- Visszaállítás előtt helyi biztonsági pillanatkép készül.

## Play modell
Első kiadás: fizetős alkalmazás a Google Play Áruházban. Egyszeri vételár, nincs előfizetés és nincs appon belüli számlázási logika.

## Android azonosító
- App név: `Számlakezelő`
- Application ID: `hu.rauch.szamlakezelo`
