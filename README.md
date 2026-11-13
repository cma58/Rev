# Rev360

Een eigen Android-app voor de Samsung Gear 360, gebouwd omdat Samsungs
`Gear 360 Manager` niet meer werkt op moderne telefoons en niet meer
gerepareerd kan worden.

**Er wordt niets op de camera geïnstalleerd.** De app gebruikt een ingang die
al in de originele firmware zit.

## Waarom niet de originele app

Gear 360 Manager praat met de camera via het **Samsung Accessory Protocol**,
door de systeemdienst `com.samsung.accessory` over RFCOMM. Dat pad is dicht:

- SAP eist apparaat-authenticatie met Samsungs white-box crypto (`libwsm2`).
  Die sleutel zit in Samsung-firmware en is niet na te maken.
- Vanaf Android 10 krijgt een gewone app zijn eigen Wi-Fi-Direct-MAC niet meer
  te zien (`02:00:00:00:00:00`), waardoor ook die route onbruikbaar is.

Decompileren van de originele APK verandert daar niets aan: de afhankelijkheid
zit in systeemonderdelen die een moderne, niet-Samsung telefoon niet heeft.

## Waarop dit wél werkt

De camera heeft een tweede, gepubliceerde ingang. Zet hem in
**Wi-Fi → Connect to Street View**, dan brengt hij zijn eigen access point op
en spreekt hij de **Open Spherical Camera API** over gewoon HTTP:
`/osc/info`, `/osc/state`, `/osc/commands/execute`.

Dat is een modus die je op de camera kiest. Geen bestanden op de SD-kaart,
geen firmware-wijziging.

## Wat de app doet

| | |
|---|---|
| Verbinden met het access point van de camera | ✅ |
| Camera-identiteit en firmware uitlezen | ✅ |
| Accu, vrije ruimte, resterende foto's | ✅ |
| Foto maken op afstand | ✅ |
| Timelapse (door de app gestuurd) | ✅ |
| Bestanden op de kaart bekijken | ✅ |
| Downloaden naar de telefoon | ✅ `Pictures/Rev360` |
| Verwijderen van de kaart | ✅ met bevestiging |
| **Live beeld** | ❌ Samsungs preview liep over een eigen, nooit gepubliceerd protocol. Bestaat niet in OSC. |
| **Video starten op afstand** | ❌ OSC is een foto-API. Video start je op de camera; de bestanden haal je daarna op. |

## Hoe de verbinding werkt

Het access point van de camera heeft geen internet. Android houdt daarom
mobiele data als standaardroute, en een gewone `URL.openConnection()` verlaat
de telefoon en komt nooit aan. Daarom:

- `CameraLink.requestCameraAp()` vraagt Android via `WifiNetworkSpecifier` om
  een verbinding die alleen voor deze app geldt. Andere apps houden hun
  mobiele data. Je hoeft niet naar Instellingen.
- Elk HTTP-verzoek gaat door `Network.openConnection()` van dat specifieke
  netwerk, niet door de standaardroute.
- Het adres van de camera wordt uit de gateway van dat netwerk gelezen, niet
  hardgecodeerd: dat verschilt per firmwareversie.

`CameraLink.currentWifi()` is het alternatief voor wie zelf in Instellingen op
het camerawifi is gaan zitten.

## API-niveau 1 en 2

De 2016-camera meldt OSC-niveau 1. Dat niveau heeft een sessie nodig rond elke
opdracht en kent `camera.listImages` in plaats van `camera.listFiles`. De
client leest `apiLevel` uit `/osc/info` en kiest zelf het juiste pad, zodat
dezelfde app ook met een niveau-2-camera werkt.

## Bouwen

```sh
export ANDROID_HOME=/pad/naar/android-sdk
gradle :app:assembleDebug
```

De APK komt in `app/build/outputs/apk/debug/`.

Let op: `settings.gradle.kts` wijst Maven Central naar `repo1.maven.org` in
plaats van `repo.maven.apache.org`. Die laatste geeft HTTP 429 vanaf gedeelde
IP-adressen.

## Status

Versie 0.1.0. Gebouwd en op APK-niveau gecontroleerd, maar **nog niet tegen een
echte camera getest** — die stond niet ter beschikking bij het bouwen. Daarom
staat er een logboek op het hoofdscherm dat alles toont wat de camera
onbewerkt antwoordt: wat er misgaat, is daarmee te zien.
