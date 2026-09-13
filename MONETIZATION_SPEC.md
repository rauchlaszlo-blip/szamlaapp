# Számlakezelő – monetizáció és próbaidő

## Üzleti modell
- Google Play letöltés: ingyenes.
- Próbaidő: 30 nap, teljes funkcionalitással.
- Próbaidő után: egyszeri vásárlás.
- Nincs előfizetés.
- A vásárlás nem fogyó, végleges jogosultságot ad.

## Google Play termék
- Terméktípus: one-time product / managed product.
- Javasolt Product ID: `lifetime_unlock`.
- Jogosultság neve az appban: `lifetime`.

## Próbaidő
- A próba az első alkalmazásindításkor kezdődik.
- `trialStartedAt` helyben tárolódik.
- A Drive backup tartalmazza a `trialStartedAt` mezőt is.
- Visszaállításkor a korábbi dátum marad érvényes; a próba nem indul újra.
- A próba alatt minden funkció elérhető, a Drive mentés is.

## Próbaidő lejárta
A próba lejártakor:
- az adatok nem törlődnek;
- a meglévő adatok megtekinthetők;
- Drive mentés/export és visszaállítás továbbra is elérhető marad;
- új számla létrehozása, módosítás, törlés és kifizetettnek jelölés zárolható;
- megjelenik a vásárlási képernyő.

Javasolt szöveg:
`A 30 napos próbaidő lejárt. Egyszeri vásárlással korlátlan ideig használhatod a Számlakezelőt.`

Gombok:
- `Megvásárolom`
- `Vásárlás visszaállítása`

## Vásárlás kezelése
- Google Play Billing Library aktuálisan támogatott verzióját kell használni.
- A terméket `queryProductDetailsAsync()` segítségével kérjük le.
- Sikeres vásárlás után a jogosultság csak `PURCHASED` állapotnál aktiválható.
- A nem fogyó vásárlást acknowledgement után tekintjük véglegesnek.
- Induláskor és visszatéréskor `queryPurchasesAsync()` ellenőrzi a meglévő jogosultságot.
- Az alkalmazás újratelepítése után a Google Play-fiókhoz tartozó vásárlás visszaállítható.

## Állapotok
- `trial_active`
- `trial_expired`
- `lifetime_unlocked`

## Biztonság
- A webes/PWA változatban nincs Play Billing, ezért a fizetési zárolás csak az Android kiadásban aktív.
- A jelenlegi Render/PWA verziót a Play Billing fejlesztés nem módosíthatja vagy törheti el.
- A Play jogosultságot natív Android oldalon kezeljük, a webes réteg csak az eredményt kapja meg.

## Android
- Application ID: `hu.rauch.szamlakezelo`
- App név: `Számlakezelő`
- AAB kiadás szükséges a Play Console feltöltéshez.
