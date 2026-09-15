# NKS Bob API

Dette er et API som tjener [frontenden til NKS Bob](https://github.com/navikt/nks-bob-frontend). Dette er et API som er lagt med Kotlin + Ktor + Exposed.

NKS-Bob er en "språkgenereringsmodell", eller "språkbehandlingsassistent" som skal hjelpe veilederne i NKS med å besvare spørsmål fra brukere som ringer inn.

## Komme i gang

For å kjøre applikasjonen lokalt:

### Kjør opp databasen
```
docker-compose up
```

### Kjør serveren
```
./gradlew run
```

Serveren kjøres på http://localhost:8080.

## Aktive samtaler

Grunnlaget for aktive samtaler ligger i `core/conversation/active` og
`core/integration`. Flyway-migrasjon `V29` oppretter en tabell med én rad per
WebSocket-forbindelse, bcrypt-eier og tidsstempler. Tabellen har ingen unik
begrensning per bruker/samtale, siden flere faner kan holde samme samtale åpen.

`ActiveConversationService.create()` registrerer forbindelser etter
eierskapskontroll, fornyer gyldige forbindelser, fjerner frakoblede/utløpte
forbindelser og henter unike aktive samtaler. Listen støtter fire
sorteringsretninger og paginering med samme databasesnapshot for side og total.
En utløpt eller slettet forbindelse kan ikke fornyes; klienten må koble til på nytt.

Tjenesten er koblet inn i WebSocket-endepunktet
(`/api/v2/conversations/{id}/messages/ws`): en tilkobling registreres når
klienten kobler til (gjenbruker eierskapssjekken som allerede fantes der),
fornyes automatisk hvert `RENEW_INTERVAL` (60s) så lenge socketen er åpen, og
fjernes når socketen lukkes. Om fornyelsen feiler (leasen har utløpt) lukkes
socketen, og klienten må koble til på nytt.

`IntegrationConversationService.create(...)` tar eksisterende services,
eventbus og et applikasjonseid coroutine-scope. Den validerer input og
kontrollerer eierskap før innholdsbehandling og skriving. Samtale med
initialmelding lagres atomisk, og Vaskemaskin-policyen gjenbrukes uten dobbel
behandling. KBS-behandling kjøres i et supervisert scope uavhengig av HTTP-kallet.
Ved mislykket oppstart etter lagring returneres `MessageProcessingNotStarted`
med lagrede ressurs-ID-er, ikke en vellykket kvittering. Behandlingen er
best-effort, uten automatisk retry eller varig kø.

**Rød sone – forstå dette grundig:** eierskap, atomisk lagring og avbrutt
bakgrunnsbehandling. Ved oppkobling må `shutdown()` kalles og fullføres før
database, HTTP-klienter og Kafka lukkes. Metoden avviser nye oppgaver, avbryter
pågående behandling og venter på opprydding.

`IntegrationConversationService` er **fortsatt ikke koblet til applikasjonen**
og har ingen ruter. Ikke gi Salesforce nye tilganger før dette og
miljøoppsettet er avklart.

`ActiveConversationStatisticsRepo` teller unike samtaler og forbindelser som
er fornyet de siste fem minuttene. `ActiveConversationMetrics` eksponerer et
konsistent snapshot og beholder siste måling ved feil. Innhenting skjer nå
periodisk (hvert `RENEW_INTERVAL`) fra en bakgrunnsjobb startet i
`Application.module()`, som registrerer/avregistrerer samlerne i appens
Prometheus-register og avbrytes ved graceful shutdown.
Globale verdier fra flere podder skal aggregeres med `max`, ikke `sum`, og
målinger eldre enn 120 sekunder skal vises som ukjente.

Modulen `jobs/delete-active-connections` kaller
`POST /api/v1/admin/jobs/delete-expired-active-connections` med Texas-maskintoken.
Endepunktet er nå implementert i `JobService`/`jobsRoutes` bak
`authenticate("MachineToken")`, på samme måte som de andre jobb-endepunktene,
og jobbens inbound-tilgang er lagt til i `accessPolicy.inbound` i
`api/.nais/dev-gcp.yaml` og `prod-gcp.yaml`.
Workflowen kjører jobbtester, men bygg/deployment er fortsatt sperret med
repository-variabelen `ACTIVE_CONNECTIONS_CLEANUP_DEPLOY_ENABLED`.
Ikke sett den til `true` før endringene er verifisert i dev-miljøet.
Nais-manifestene planlegger kjøring kl. 03:15 i tidssonen `Europe/Oslo`.

---

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #team-nks-ai-og-automatisering.

Mer om teamet finner du her:
https://teamkatalog.nav.no/team/415e12bc-61fb-4579-840a-c9307765f2fc
