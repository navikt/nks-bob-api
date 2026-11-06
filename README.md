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

Grunnlaget for aktive forbindelser ligger i `core/conversation/active`.
Integrasjons-API-et bruker ikke registeret. Flyway-migrasjon `V29` oppretter en tabell med én rad per
WebSocket-forbindelse, bcrypt-eier og tidsstempler. Tabellen har ingen unik
begrensning per bruker/samtale, siden flere faner kan holde samme samtale åpen.

`ActiveConversationService.create()` registrerer forbindelser etter
eierskapskontroll, fornyer gyldige forbindelser og fjerner frakoblede/utløpte
forbindelser. Oppslag, sortering, paginering og statistikk for aktive samtaler
er fjernet fordi integrasjonen ikke lenger bruker dem.
En utløpt eller slettet forbindelse kan ikke fornyes; klienten må koble til på nytt.

Tjenesten er koblet inn i WebSocket-endepunktet
(`/api/v2/conversations/{id}/messages/ws`): en tilkobling registreres når
klienten kobler til (gjenbruker eierskapssjekken som allerede fantes der),
fornyes automatisk hvert `RENEW_INTERVAL` (60s) så lenge socketen er åpen, og
fjernes når socketen lukkes. Om fornyelsen feiler (leasen har utløpt) lukkes
socketen, og klienten må koble til på nytt.

WebSocket-endepunktet åpner ikke strømmen før poddens Kafka-consumer har fått
partisjoner og etablert startposisjonen for alle partisjonene. Før det lukkes
tilkoblingen med kode `1013` (`TRY_AGAIN_LATER`), uten aktiv registrering.
Eksisterende tilkoblinger lukkes med samme kode ved rebalance eller når consumeren
stopper. Klienten må koble til på nytt og hente lagret meldingstilstand via REST.
Dette gjelder også når Kafka er deaktivert. Appens globale readiness er uendret.

Kafka-publisering går via en minnekø med plass til 1024 hendelser og én
bakgrunnsarbeider. Kafka-feil og full kø avbryter ikke SSE- eller KBS-behandlingen.
Tapte hendelser telles i `nksbobapi_kafka_publish_failures_total` med årsak,
og feil logges høyst hvert 30. sekund uten meldingsinnhold.
Ved shutdown får køen to sekunder til å tømmes før resterende hendelser forkastes.
Dette er best-effort-levering, ikke en varig kø. Websocket-klienter må kunne hente
lagret meldingstilstand via REST etter et avbrudd.

Tap øker en publiseringsgenerasjon som følger Kafka-recordene i headere,
uten å endre JSON-payloaden. En egen arbeider sender en `NoOp`-heartbeat
hvert femte sekund med publisher-ID, generasjon og sendetid i headere.
Heartbeats går utenom den vanlige hendelseskøen og sendes ikke til frontend.
Alle API-podder observerer disse, også når svaret produseres på en annen pod.
Ved ny tapsgenerasjon tømmes replay-bufferen, og åpne meldings- og
varslings-WebSocketer lukkes med `1013`. Forsinkede hendelser fra eldre
publiseringsgenerasjoner forkastes.

Hvis Kafka ikke kan levere et tapsignal, utløper publisherens heartbeat-lease
etter 30 sekunder. En lokal sjekk hvert sekund lukker da forbindelsene og
tømmer replay. Oppkobling krever også at podden har konsumert sin egen
ferske heartbeat. Dette oppdager producer-/consumer-avbrudd uten polling
fra frontend. Remote leases registreres gjennom heartbeat eller første
hendelse med publisher-headere. Publisher-sporing er begrenset til 512 ID-er; overskridelse
utløser samme resynkronisering. Heartbeat-ferskhet forutsetter
synkroniserte klokker på API-poddene. Utløp måles med en monoton klokke.

Resynkronisering gjelder konservativt alle åpne strømmer på en pod, ikke
bare samtalen der tapet oppstod. Det unngår en egen, ubundet kø med
samtale-ID-er som må varsles. Klienten må ved `1013` hente lagret
meldingstilstand via REST og koble til igjen med backoff. Meldingslevering
er fortsatt best-effort; garantien er et resynkroniseringssignal ved tap
eller utløpt lease, ikke varig levering av hver hendelse.
Signalene telles i `nksbobapi_kafka_stream_invalidations_total` med faste
årsaker, uten bruker- eller samtaleidentifikatorer.

Kafka-headere og `NoOp`-payloaden lar eldre podder fortsatt lese ordinære
hendelser under deployment. Tapsignalisering og heartbeat-lease gjelder
først fullt ut når alle API-podder kjører den nye versjonen.

`IntegrationConversationService.create(...)` tar eksisterende services,
eventbus og et applikasjonseid coroutine-scope. Den validerer input og
kontrollerer eierskap før innholdsbehandling og skriving. Samtale med
initialmelding lagres atomisk, og Vaskemaskin-policyen gjenbrukes uten dobbel
behandling. KBS-behandling kjøres i et supervisert scope uavhengig av HTTP-kallet.
Hvis oppstarten av behandlingen mislykkes etter at meldingen er lagret,
returnerer vi `MessageProcessingNotStarted` med HTTP 409 og de lagrede
ressurs-ID-ene, ikke en vellykket kvittering. Klienten skal ikke prøve på
nytt, fordi meldingen allerede er lagret. Behandlingen er best-effort, uten
automatisk retry eller varig kø.

**Rød sone – forstå dette grundig:** eierskap, atomisk lagring og avbrutt
bakgrunnsbehandling. Ved oppkobling må `shutdown()` kalles og fullføres før
database, HTTP-klienter og Kafka lukkes. Metoden avviser nye oppgaver, avbryter
pågående behandling og venter på opprydding.

### Integrasjonsendepunkter

`IntegrationConversationService` er nå koblet inn i `Application.module()` og
eksponert via `POST {ROOT}`
(`ROOT = /api/v1/integrations/users/{navIdent}/conversations`, se
`core/integration/Api.kt`). `shutdown()` kalles og avventes (`runBlocking`) i
en `ApplicationStopping`-handler, slik at den fullfører før database,
HTTP-klienter og Kafka lukkes i `ApplicationStopped`.

Salesforce oppretter en ny samtale med `title` og `initialMessage`. Hver
opprettelse gir en ny samtale-ID; integrasjonen kan ikke velge en aktiv
samtale eller legge meldinger i en eksisterende samtale. Rutene
`POST {ROOT}/active` og `POST {ROOT}/{id}/messages` er fjernet.
Den ordinære chatten kan fortsatt legge meldinger i eksisterende samtaler.
Payloaden støtter fortsatt opprettelse uten initialmelding.

### WebSocket-varsling om nye integrasjonssamtaler

Frontend kan abonnere på `GET /api/v2/conversations/notifications/ws` med
WebSocket-upgrade, gjennom samme innloggingsmekanisme som vanlig chat.
Ruten krever bruker-token med `NAVident` og fremtidig `exp`, samt en
`Origin` fra samme allowlist som CORS. Ikke legg token i URL.
Forbindelsen lukkes når tokenet utløper.

Etter at integrasjonen har lagret en ny samtale, mottar tilkoblede klienter
for samtalens eier en tekst-frame:

```json
{"type":"ConversationCreated","conversationId":"00000000-0000-0000-0000-000000000001"}
```

Varslet inneholder verken mottaker, tittel eller meldingsinnhold. Backend
publiserer også når samtalen er lagret, men etterfølgende KBS-behandling
ikke starter. Feil før lagring gir ikke varsel. Samtaler opprettet gjennom
den ordinære chatten utløser ikke dette varslet.

Kafka distribuerer hendelsen til alle API-podder. Hver pod henter samtalens
eierhash én gang per hendelse og sammenligner med de lokalt tilkoblede
brukerne før sending. Hendelsen legges ikke i samtalenes replay-historikk
og sendes ikke på den eksisterende meldings-WebSocketen.

Varslingsstrømmen er live-only og best-effort, uten polling. Hendelser fra
før oppkobling eller under nettverksbrudd, Kafka-feil eller deployment kan
gå tapt. Det finnes ingen varig varslingsstatus eller levering ved neste
innlogging. Samtalene ligger fortsatt i den vanlige historikken.

Ruten tillater maksimalt 512 forbindelser per pod og fem per bruker per
pod. Hver forbindelse har en buffer på 32 varsler. Ved manglende
Kafka-readiness, eierskapsoppslagsfeil, full buffer eller kapasitetsgrense
lukkes forbindelsen med kode `1013`. Klienten skal koble til igjen med
eksponentiell backoff og jitter, uten å anta at tapte varsler spilles av.
Den lokale fordeleren har plass til 128 ventende opprettelseshendelser.
Hvis den mister hendelser ved overbelastning, lukkes varslingsforbindelsene
med `1013` i stedet for at tapet skjules. En lokal sekvensgrense hindrer at
en ny forbindelse får hendelser mottatt før forbindelsen ble registrert.
Kode `1008` betyr blant annet manglende/utløpt brukeridentitet, avvist
Origin eller at klienten sendte applikasjonsdata på server-push-strømmen.

Frontend-koblingen gjenstår i `navikt/nks-bob-frontend`: Hold forbindelsen
på appnivå, håndter tokenfornyelse og gjenoppkobling, og unngå doble varsler
for samme samtale-ID. Vis en beskjed med en intern lenke til samtalen uten
å bytte samtale automatisk. Når lenken følges, brukes eksisterende
eierskapsbeskyttet REST og meldingsstrømming.

Observerbarhet uten brukeridentifikatorer finnes i
`nksbobapi_notification_websocket_connections`,
`nksbobapi_conversation_notifications_sent_total` og
`nksbobapi_notification_stream_failures_total` (fast `reason`-label).
Kafka-publiseringsfeil telles fortsatt i
`nksbobapi_kafka_publish_failures_total`.

Opprettelse har ingen idempotensnøkkel. En gjentatt POST kan opprette en
duplikat samtale, også hvis klienten ikke mottok svaret på første forsøk.
Salesforce må ikke innføre automatisk retry uten en avtalt løsning for dette.

Før deployment må Salesforce slutte å kalle de fjernede rutene. Verifiser
i dev at to opprettelser gir ulike samtaler, at en eksisterende samtale
ikke endres, og at vanlig chat og WebSocket-strømming fortsatt virker.
Bruk syntetiske testdata. Frontend-varsling må verifiseres separat i
frontend-repoet før hele brukerflyten er klar.

Deploy backend til alle podder før frontend tar varslingsruten i bruk.
Verifiser med to brukere at bare riktig eier får varsel, også når
opprettelse og WebSocket håndteres av ulike podder. Kontroller
gjenoppkobling, tokenutløp og uendret meldingsstrømming. Ved rollback
deaktiveres frontend-abonnementet før backend reverseres.

Forbindelsesregisteret, cleanup-jobben og V29 beholdes i denne endringen.
Ikke stopp cleanup mens registeret fortsatt brukes eller inneholder rader
som skal utløpe. Ingen databasemigrasjon eller endring av tokenvalideringen inngår, så
rollback av backend-koden krever ikke rollback av databaseskjemaet.
Rollback må samordnes med Salesforce. Følg med på feil ved opprettelse og
oppstart av behandling gjennom eksisterende logger og metrikker, uten
mottaker eller meldingsinnhold som labels.

Integrasjonens opprettelsesrute er beskyttet av `authenticate("Maskinporten")`
(`app/plugins/Security.kt`), som validerer et Maskinporten-utstedt token mot et
eget JWKS (`MaskinportenConfig`, env `MASKINPORTEN_ISSUER` / `MASKINPORTEN_JWKS_URI`
— settes automatisk av Nais når `maskinporten.enabled: true`) og krever at
`scope`-claimet inneholder `NKS_BOB_API_MASKINPORTEN_SCOPE`
(`nav:nksbobapi/integrations`, generert fra Nais-feltene `product: nksbobapi`
og `name: integrations`). Feil returneres som `IntegrationProblem`
(RFC 7807-stil) i stedet for appens vanlige `ErrorResponse`, slik at eksterne
konsumenter får en stabil feilkontrakt.

**Viktig: ingen reell tilgang er gitt ennå.** `maskinporten.scopes.exposes` i
`api/.nais/{dev,prod}-gcp.yaml` har bevisst en tom `consumers`-liste — uten en
registrert consumer (org.nr.) kan ingen faktisk hente et gyldig token for dette
scopet, så koden kan deployes trygt uten å åpne for Salesforce. **Ikke legg til
Salesforce sitt org.nr. i `consumers` før tilgang og miljøoppsett er avklart
med sikkerhetsteamet.**

Antakelser gjort ved implementasjon (bør verifiseres før reell utrulling):
- Maskinporten er valgt fremfor Azure AD/TokenX fordi Salesforce er en ekstern
  partner (jf. Nav-konvensjon for maskin-til-maskin fra eksterne organisasjoner).
- Auth-valideringen sjekker kun `scope`-claimet, ikke hvilken organisasjon
  (`consumer_org`) som kaller — bør vurderes om det trengs strengere binding
  mot en spesifikk Salesforce-organisasjon.
- Denne integrasjonsflaten er ny/uverifisert i dette repoet og bør
  sikkerhetsgjennomgås før den tas i bruk i prod (🔴 rød sone for
  auth-oppsettet, i tillegg til den eksisterende røde sonen i
  `IntegrationConversationService` selv).

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
