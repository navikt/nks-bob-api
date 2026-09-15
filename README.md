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

## Aktive samtaler – påbegynt

Grunnlaget for aktive samtaler ligger i `core/conversation/active` og
`core/integration`. Flyway-migrasjon `V29` oppretter en tabell med én rad per
WebSocket-forbindelse, bcrypt-eier og tidsstempler. Tabellen har ingen unik
begrensning per bruker/samtale, siden flere faner kan holde samme samtale åpen.

`ActiveConversationService.create()` registrerer forbindelser etter
eierskapskontroll, fornyer gyldige forbindelser, fjerner frakoblede/utløpte
forbindelser og henter unike aktive samtaler. Listen støtter fire
sorteringsretninger og paginering med samme databasesnapshot for side og total.
En utløpt eller slettet forbindelse kan ikke fornyes; klienten må koble til på nytt.

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

Dette er **ikke en ferdig funksjon**. Services er ikke koblet til applikasjonen.
WebSocket-registrering/fornyelse, tilgangsstyring og nye ruter gjenstår.
Ikke gi Salesforce nye tilganger før dette og miljøoppsettet er avklart.

`ActiveConversationStatisticsRepo` teller unike samtaler og forbindelser som
er fornyet de siste fem minuttene. `ActiveConversationMetrics` eksponerer et
konsistent snapshot og beholder siste måling ved feil, men periodisk innhenting
og registrering i applikasjonens metrikkregister er ikke koblet på ennå.
Globale verdier fra flere podder skal aggregeres med `max`, ikke `sum`, og
målinger eldre enn 120 sekunder skal vises som ukjente.

Modulen `jobs/delete-active-connections` kaller
`POST /api/v1/admin/jobs/delete-expired-active-connections` med Texas-maskintoken.
API-endepunktet og jobbens inbound-tillatelse er ennå ikke implementert.
Workflowen kjører jobbtester, men bygg/deployment er sperret med
repository-variabelen `ACTIVE_CONNECTIONS_CLEANUP_DEPLOY_ENABLED`.
Ikke sett den til `true` før API, autorisasjon og opprydding er ferdige.
Nais-manifestene planlegger da kjøring kl. 03:15 i tidssonen `Europe/Oslo`.

---

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #team-nks-ai-og-automatisering.

Mer om teamet finner du her:
https://teamkatalog.nav.no/team/415e12bc-61fb-4579-840a-c9307765f2fc
