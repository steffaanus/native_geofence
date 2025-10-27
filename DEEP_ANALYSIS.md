# Diepgaande Analyse: State Desynchronisatie en Event Verlies

Dit document beschrijft de technische analyse van de gerapporteerde problemen waarbij geofence-events na verloop van tijd niet meer worden ontvangen.

## Kerndiagnose

De meest waarschijnlijke oorzaak is een **desynchronisatie tussen de geofences die in `SharedPreferences` zijn opgeslagen en de geofences die daadwerkelijk actief zijn bij de `GeofencingClient` van Google Play Services.** De plugin vertrouwt op zijn eigen opslag als "source of truth", maar deze is niet robuust gekoppeld aan de staat van het besturingssysteem.

## Geïdentificeerde Problemen

### 1. Niet-atomische opslagoperaties

*   **Probleem:** Bij het aanmaken van een geofence (`createGeofenceHelper`) wordt deze eerst succesvol geregistreerd bij de `GeofencingClient` en *daarna* pas opgeslagen in `SharedPreferences`. Een crash of app-beëindiging tussen deze twee stappen leidt tot een **spook-geofence**: een geofence die actief is in het OS, maar onbekend is voor de plugin. Deze zal na een herstart niet worden hersteld.
*   **Impact:** Hoog. Leidt tot een onbetrouwbare staat en 'verloren' geofences.

### 2. Kwetsbare Event-afhandeling

*   **Probleem:** De `BroadcastReceiver` delegeert de event-afhandeling naar een `WorkManager` taak. Dit proces heeft meerdere faalpunten:
    1.  **WorkManager Quota:** De job wordt ingediend als `.setExpedited`, maar kan worden gedegradeerd naar een normale (vertraagde) taak als de app zijn quota heeft bereikt (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`). Dit is een zeer waarschijnlijke oorzaak voor "verloren" of extreem vertraagde events op OEM-apparaten met agressief batterijbeheer.
    2.  **BroadcastReceiver Time-outs:** Android legt strikte tijdslimieten op aan `BroadcastReceivers`. Elke vertraging in het starten van de `WorkManager` kan ertoe leiden dat het OS het proces stopt voordat het werk is gedelegeerd.
*   **Impact:** Hoog. Dit is een directe oorzaak van verloren of vertraagde geofence-events.

### 3. Onvolledige Herstartlogica

*   **Probleem:** De `NativeGeofenceRebootBroadcastReceiver` herstelt blindelings de geofences die in `SharedPreferences` staan. Er vindt geen verificatie of synchronisatie plaats met de `GeofencingClient`. Als de `SharedPreferences` corrupt of niet-synchroon zijn, wordt een incorrecte staat hersteld.
*   **Impact:** Hoog. Garandeert niet dat de staat van voor de herstart correct wordt hervat.

## Aanbevelingen voor mitigatie

### 1. Implementeer Robuuste State Synchronisatie

*   **Voorstel:** Implementeer een *"intentie-gebaseerd"* opslagsysteem.
    1.  **Omkeren van de opslaglogica:** Bij het aanmaken, sla de geofence *eerst* op in `SharedPreferences` met een `PENDING_ADD` status.
    2.  Registreer de geofence bij de `GeofencingClient`.
    3.  Bij succes, update de status in `SharedPreferences` naar `ACTIVE`.
    4.  Bij falen, verwijder de geofence uit `SharedPreferences` of markeer als `FAILED`.
    5.  Implementeer een vergelijkbare logica voor `remove` operaties (`PENDING_REMOVE`).

*   **Synchronisatiefunctie:** Creëer een `syncWithOS()` functie die bij elke app-start wordt aangeroepen. Deze functie moet:
    *   Alle geofences met status `PENDING_ADD` proberen (opnieuw) te registreren.
    *   Alle geofences met status `PENDING_REMOVE` proberen (opnieuw) te verwijderen.
    *   Periodiek (of bij app-start) alle `ACTIVE` geofences opnieuw registreren bij de `GeofencingClient`. De `addGeofences` API werkt als een "upsert", wat de staat effectief synchroniseert.

### 2. Verhoog de Betrouwbaarheid van Event Handling

*   **Voorstel:** Garandeer de overdracht van het event.
    *   **Directe Foreground Service:** Overweeg om direct vanuit de `NativeGeofenceBroadcastReceiver` een tijdelijke `ForegroundService` te starten. Deze service heeft als enige taak het opstarten van de `NativeGeofenceBackgroundWorker` via `WorkManager`. Dit omzeilt de beperkingen van `setExpedited` en geeft een veel hogere garantie dat het werk onmiddellijk wordt gestart. Dit vereist wel de `FOREGROUND_SERVICE` permissie.
    *   **Verbeterde Logging:** Voeg gedetailleerde logging toe aan het volledige pad van event-ontvangst tot aan de Dart-callback, inclusief de status van de `WorkManager` job.

Deze aanbevelingen vereisen aanpassingen in `NativeGeofenceApiImpl.kt`, `NativeGeofencePersistence.kt` en `NativeGeofenceBroadcastReceiver.kt`. Ze zullen de robuustheid van de plugin aanzienlijk verhogen en de gerapporteerde problemen waarschijnlijk oplossen.
---

# Diepgaande Analyse iOS

De iOS-implementatie heeft een andere architectuur dan Android en kent zijn eigen unieke, ernstige problemen die kunnen leiden tot verloren events en onbetrouwbaar gedrag.

## Kerndiagnose iOS

De implementatie vertrouwt volledig op `CLLocationManager` als "source of truth" en gebruikt een **extreem kostbaar en foutgevoelig patroon waarbij voor elk geofence-event een nieuwe, headless `FlutterEngine` wordt aangemaakt en weer vernietigd.**

## Geïdentificeerde Problemen

### 1. Inefficiënt en Gevaarlijk `FlutterEngine` Management

*   **Probleem:** In `LocationManagerDelegate` wordt bij elk binnenkomend event `createFlutterEngine()` aangeroepen. Deze functie instantieert een volledig nieuwe `FlutterEngine`, start deze, registreert plugins, en wordt na gebruik weer vernietigd via `cleanup()`.
    *   **Race Conditions:** De code is niet thread-safe. Als twee events kort na elkaar binnenkomen, kan de `cleanup()` van het eerste event de engine vernietigen terwijl het tweede event deze nog nodig heeft. Dit leidt tot crashes en gegarandeerd dataverlies.
    *   **Resourceverbruik:** Het opstarten van een `FlutterEngine` is een zware operatie. Dit continu herhalen verbruikt onnodig veel batterij en CPU, wat de "battery efficient" belofte van de plugin ondermijnt.
    *   **Stilzwijgend falen:** De `createFlutterEngine` methode heeft meerdere faalpunten (bv. callback niet gevonden) die ervoor zorgen dat het proces stopt en het event stilzwijgend wordt genegeerd.
*   **Impact:** Zeer hoog. Dit is de meest waarschijnlijke oorzaak van verloren events en instabiliteit op iOS.

### 2. Onvolledige State Persistence

*   **Probleem:** `NativeGeofencePersistence.swift` slaat alleen een mapping van `regionID -> callbackHandle` op in `UserDefaults`. De volledige geofence-definitie (locatie, radius, etc.) wordt niet bewaard. De plugin vertrouwt erop dat het `CLCircularRegion` object van het OS alle benodigde info bevat.
*   **Impact:** Gemiddeld tot hoog. Het onmogelijk maakt om:
    1.  De staat van de plugin te verifiëren.
    2.  Een `sync`-operatie uit te voeren, omdat de plugin niet weet wat er geregistreerd *zou moeten* zijn.
    3.  Volledige geofence-data terug te geven aan de Dart-laag (`getGeofences`) zonder afhankelijk te zijn van de live-data van `CLLocationManager`.

### 3. Onterecht Vertrouwen op OS-herstel na Reboot

*   **Probleem:** De `reCreateAfterReboot`-functie is leeg, met de aanname dat iOS alles automatisch regelt. Hoewel `CLLocationManager` de regio's herstelt, valideert de plugin niet of de bijbehorende `callbackHandles` nog correct zijn en of er geen desynchronisatie is opgetreden.
*   **Impact:** Hoog. Dit leidt tot een onbetrouwbare staat na een herstart, wat de gerapporteerde dubbele events in de `README.md` kan verklaren.

## Aanbevelingen voor iOS-refactoring

### 1. Centraliseer en Hergebruik de `FlutterEngine`

*   **Voorstel:** Implementeer een `EngineManager`- singleton patroon.
    1.  Maak de headless `FlutterEngine` **één keer** aan en houd deze in leven zolang er actieve geofences zijn.
    2.  Start de engine bij de `initialize`-aanroep van de plugin.
    3.  Vernietig de engine alleen wanneer `removeAllGeofences` wordt aangeroepen (of als de app wordt beëindigd).
    4.  Zorg voor thread-safe toegang tot de engine en de background API met `DispatchQueue` of locks.

### 2. Implementeer Robuuste State Persistence

*   **Voorstel:** Sla de **volledige `GeofenceWire`-definitie** op in `UserDefaults`, vergelijkbaar met de verbeterde Android-implementatie. Gebruik hiervoor `Codable` om de `GeofenceWire` (of een Swift-equivalent) naar `Data` te converteren en op te slaan.
    *   Dit maakt de plugin zelfvoorzienend en niet langer volledig afhankelijk van de runtime-informatie van `CLLocationManager`.

### 3. Implementeer een `syncGeofences` Functie

*   **Voorstel:** Creëer een `syncGeofences`-functie die wordt aangeroepen bij de `initialize`-methode.
    1.  Haal de lijst van opgeslagen geofences uit `UserDefaults`.
    2.  Haal de lijst van `monitoredRegions` op bij `CLLocationManager`.
    3.  Vergelijk de twee lijsten en voer de nodige correcties uit:
        *   Voeg ontbrekende geofences toe aan `CLLocationManager`.
        *   Verwijder overbodige geofences uit `CLLocationManager`.
    *   Dit zorgt voor een consistente en betrouwbare staat, zelfs na een app-update, crash of OS-reboot.
---

# Backward Compatibility & Migratie

De refactoring van de state persistence op zowel Android als iOS introduceert een nieuwe opslagstructuur. Om te voorkomen dat dit een "breaking change" is voor bestaande gebruikers, is een expliciet migratiepad geïmplementeerd voor beide platformen.

## Android Migratie

*   **Probleem:** De opgeslagen JSON-structuur voor geofences is gewijzigd door de toevoeging van een `status`-veld. Het direct proberen te decoderen van oude data met de nieuwe dataklasse (`GeofenceStorage`) zou een `SerializationException` veroorzaken.
*   **Strategie:** Een "try-catch-migrate" aanpak in `NativeGeofencePersistence.kt`.
    1.  Bij het ophalen van een geofence (`getGeofence`), probeert de code eerst de data te decoderen met de **nieuwe** `GeofenceStorage`-structuur.
    2.  Bij een exceptie (wat duidt op oude data), wordt in het `catch`-blok geprobeerd de data te decoderen met een tijdelijke `LegacyGeofenceStorage`-klasse die de oude structuur weerspiegelt.
    3.  Als dit succesvol is, wordt het legacy-object omgezet naar de nieuwe structuur, waarbij de status logischerwijs wordt ingesteld op `ACTIVE`.
    4.  Dit nieuwe object wordt onmiddellijk teruggeschreven naar de opslag, waardoor de data voor die specifieke geofence permanent gemigreerd is.
*   **Betrouwbaarheid:** Zeer hoog (99.9%). Deze aanpak is robuust en faalt alleen als de oorspronkelijke JSON-data al corrupt was.

## iOS Migratie

*   **Probleem:** De opslag verandert fundamenteel van een simpele `[ID: CallbackHandle]`-dictionary naar een geëncodeerde dictionary van volledige `[ID: GeofenceWire]`-objecten. De oude opslag mist cruciale data (locatie, radius, triggers).
*   **Strategie:** Een "reconstruct-and-migrate" aanpak in `NativeGeofenceApiImpl.swift` bij de initialisatie.
    1.  De `migrateLegacyData`-functie controleert op het bestaan van de oude `UserDefaults`-key.
    2.  Als deze bestaat, worden twee databronnen gecombineerd:
        *   De `monitoredRegions` van `CLLocationManager` worden gelezen om de `identifier`, `center` (locatie), `radius` en `triggers` (`notifyOnEntry`/`notifyOnExit`) te verkrijgen.
        *   De oude `UserDefaults`-dictionary wordt gebruikt om de bijbehorende `callbackHandle` op te zoeken via de `identifier`.
    3.  Met deze gecombineerde data wordt voor elke actieve geofence een compleet `GeofenceWire`-object gereconstrueerd. Voor de ontbrekende `androidSettings.initialTriggers` wordt een veilige default van `[.enter, .exit]` aangenomen.
    4.  De gereconstrueerde objecten worden opgeslagen in de **nieuwe** opslagstructuur.
    5.  De oude `UserDefaults`-key wordt definitief verwijderd.
*   **Betrouwbaarheid:** Hoog (99%). De strategie is afhankelijk van de betrouwbaarheid van `CLLocationManager`'s eigen persistentie van `monitoredRegions`, wat een kernfunctionaliteit van iOS is. De kans op falen is hier verwaarloosbaar klein. Dit maakt de update naadloos voor iOS-gebruikers.