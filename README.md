# Syncer

App Android che copia i dati del tuo account Google in cartelle del telefono scelte da te, in
formati aperti e leggibili da altre app (per esempio Markor, Obsidian, Syncthing, qualunque client
di calendario o rubrica):

| Dato | Origine | File prodotti | Quando si aggiorna |
|---|---|---|---|
| **Calendari** | calendari dell'account Google sincronizzati sul telefono | un file `.ics` per calendario (iCalendar RFC 5545) | pochi secondi dopo ogni modifica, più l'intervallo periodico |
| **Contatti** | contatti dell'account Google sul telefono | un unico `Contatti.vcf` (vCard 3.0) | pochi secondi dopo ogni modifica, più l'intervallo periodico |
| **Attività** | Google Tasks (API ufficiale) | un file `<elenco>.todo.txt` per elenco (formato todo.txt) | ogni N minuti (minimo 15) |
| **Note** | Google Keep: export di Google Takeout (account personali) oppure API di Keep (solo Google Workspace) | un file `.md` per nota, allegati in `attachments/` | ogni N minuti, appena trovi un nuovo export |
| **Password** | Gestore password di Google (trasferimento sicuro di Android, oppure file CSV) | un database KeePass `Password Google.kdbx` cifrato | quando importi (Google richiede la tua conferma ogni volta) |

La sincronizzazione è **a senso unico** (Google → file): le modifiche fatte ai file non vengono
rimandate a Google.

## Funzioni

- **Configurazione guidata** al primo avvio:
  1. importazione facoltativa di un backup delle impostazioni;
  2. scelta dell'account Google (selettore di sistema);
  3. dati da sincronizzare e una cartella per ciascun tipo (calendari, attività, note, contatti);
  4. quando sincronizzare (alla modifica / ogni N minuti);
  5. cartella per il backup delle impostazioni;
  6. permessi (calendario, contatti, notifiche) e autorizzazioni Google.
- **Impostazioni** con tutte le stesse opzioni, modificabili in qualunque momento.
- **Backup automatico delle impostazioni**: a ogni modifica viene salvato nella cartella scelta un
  nuovo file `syncer-settings_AAAA-MM-GG_hh-mm-ss.json`. I backup precedenti non vengono mai
  sovrascritti né cancellati.
- **Importazione** di un backup, sia al primo avvio sia dalle impostazioni (riapre la
  configurazione guidata già compilata, per confermare account e cartelle).
- Schermata principale con stato di ogni sincronizzazione, pulsante *Sincronizza ora* e azioni
  rapide per risolvere i problemi (permesso mancante, autorizzazione, cartella non accessibile).
- Notifica quando una sincronizzazione ha bisogno di te.

## Come funziona la sincronizzazione

- **Alla modifica** (calendari e contatti): l'app registra presso Android un lavoro che scatta
  quando cambiano i dati del Calendario o della Rubrica (trigger `content://` di JobScheduler via
  WorkManager). Non c'è alcun servizio sempre attivo: il consumo di batteria è trascurabile.
- **Periodica** (tutti i dati): WorkManager, ogni N minuti scelti da te. Android non esegue lavori
  periodici più spesso di ogni **15 minuti**, e in Doze può ritardarli: è l'unica opzione per
  Google Tasks e Keep, che non notificano le modifiche. Richiede una connessione di rete.
- **Solo i file cambiati vengono riscritti** (confronto tramite hash), così strumenti come
  Syncthing non vedono modifiche inutili.
- **I file eliminati alla fonte vengono eliminati** (opzione disattivabile): per esempio una nota
  cancellata o un elenco rimosso. Syncer tiene traccia dei file che ha creato e **non tocca mai
  altri file** presenti nella cartella. Se una fonte restituisce zero elementi (per esempio la
  sincronizzazione dell'account è disattivata) i file esistenti non vengono modificati.
- Le cartelle sono scelte con il selettore di sistema (Storage Access Framework): puoi usare la
  memoria interna, una scheda SD o un'altra app che espone cartelle. Usa cartelle dedicate.

## Note di Google Keep: perché serve Google Takeout

Google **non offre un'API di Keep per gli account personali** (@gmail.com): l'
[API ufficiale](https://developers.google.com/workspace/keep/api/guides) è riservata agli account
Google Workspace, e deve essere abilitata dall'amministratore del dominio. Per questo l'app offre
due origini:

- **Export di Google Takeout** (account personali): vai su
  [takeout.google.com](https://takeout.google.com), seleziona solo *Keep* e scarica l'export.
  Salva lo zip (anche multiparte, `takeout-…-001.zip`) oppure la cartella estratta
  (`Takeout/Keep`) nella *Cartella degli export Takeout* scelta nelle impostazioni. A ogni
  sincronizzazione Syncer usa l'export più recente, converte ogni nota in Markdown e copia le
  immagini in `attachments/`. Se l'export non è cambiato non viene rielaborato. Takeout può anche
  essere programmato per ripetersi ogni 2 mesi.
- **API di Google Keep** (solo Google Workspace): sincronizzazione automatica e periodica delle
  note di testo ed elenchi. Serve lo scope `keep.readonly` nel client OAuth (vedi sotto).

Le librerie non ufficiali che leggono Keep con credenziali "master" dell'account violano i termini
di Google e possono far bloccare l'account: per questo non sono state usate.

## Password di Google → database KeePass

Google non permette alle app di leggere le password salvate in automatico, quindi ogni
importazione va confermata da te. Il risultato è il file `Password Google.kdbx` (KeePass 4:
AES-256 con chiave derivata da Argon2id), che si apre con KeePassDX, KeePassXC, KeePass2Android,
Strongbox e simili.

- **Importa dal Gestore password di Google** (consigliato). Usa il trasferimento sicuro delle
  credenziali di Android (standard FIDO Credential Exchange): Android mostra le app che possono
  esportare, scegli *Gestore password di Google* e confermi. Le password passano direttamente a
  Syncer, cifrate, senza creare file in chiaro. Serve Google Play services aggiornato: la funzione
  è stata distribuita da giugno 2026.
- **Importa da file CSV** (alternativa). Dal Gestore password di Google (*Impostazioni › Esporta
  password*) salvi un CSV e lo scegli in Syncer. Dopo la conversione Syncer propone di eliminare
  il CSV, che contiene tutte le password in chiaro.

Ogni importazione sostituisce il database con il contenuto attuale del Gestore password. Le note
delle password vanno nel campo Note e le app Android nel campo `AndroidApp` (utile all'autofill di
KeePassDX). Le passkey non vengono esportate.

**La password del database** si imposta in *Impostazioni › Password (Google)*. Viene salvata solo
sul telefono, cifrata con una chiave del Keystore Android, e **non finisce mai nei backup delle
impostazioni**: dopo un'importazione su un altro telefono va reinserita. Per cambiarla serve
quella attuale, e il database esistente viene ricifrato con la nuova. Se l'hai dimenticata puoi
impostarne una nuova: il file già esportato resta con la vecchia password fino alla prossima
importazione. Se una trasmissione non contiene password, il database esistente non viene toccato.

## Configurare Google Cloud (necessario per Google Tasks)

Calendari, contatti e note da Takeout funzionano **senza** questa configurazione. Google Tasks (e
l'API di Keep) richiedono invece un client OAuth collegato all'app. Si fa una volta sola, gratis:

1. Apri la [Google Cloud Console](https://console.cloud.google.com/) e crea un progetto.
2. *API e servizi › Libreria*: abilita **Google Tasks API** (e **Google Keep API** se usi un
   account Workspace).
3. *API e servizi › Schermata consenso OAuth*: tipo di utente **Esterno** (o *Interno* per
   Workspace), inserisci nome app ed e-mail. Negli ambiti aggiungi
   `https://www.googleapis.com/auth/tasks.readonly` (ed eventualmente
   `https://www.googleapis.com/auth/keep.readonly`).
   Con lo stato di pubblicazione *Test* aggiungi il tuo account tra gli *utenti di test*:
   in questo stato Google può chiedere di rinnovare il consenso dopo 7 giorni. Per evitarlo puoi
   *pubblicare* l'app: per uso personale non serve la verifica, vedrai solo l'avviso
   "Google non ha verificato questa app" (scegli *Avanzate › Vai a …*).
4. *Credenziali › Crea credenziali › ID client OAuth*, tipo **Android**:
   - nome del pacchetto: `io.github.waph1.syncer`
   - impronta del certificato SHA-1: la trovi nell'app in *Impostazioni › Info* (con il pulsante
     per copiarla), oppure con `./gradlew signingReport`.
5. Nell'app: *Impostazioni › Permessi e autorizzazioni › Google Tasks › Autorizza* e concedi
   l'accesso in sola lettura.

L'autorizzazione usa l'account già presente sul telefono tramite Google Play services
(`AuthorizationClient`): nessuna password o chiave segreta viene salvata nell'app.

> Lo SHA-1 dipende dalla chiave con cui è firmato l'APK: se reinstalli un APK firmato con
> un'altra chiave (per esempio un debug compilato su un altro PC), va aggiunto anche quello.

## Installare l'APK pronto

Nella cartella [`apk/`](apk/) c'è `Syncer-1.2.0.apk`, già compilato e firmato: copialo sul
telefono e aprilo (Android chiederà di consentire l'installazione da quella app). Per Google Tasks
registra su Google Cloud (vedi la sezione sopra) l'impronta del suo certificato:

```
SHA-1: F3:06:6B:4F:94:DC:84:7C:8B:37:99:02:66:85:9C:F4:40:02:70:48
```

Un APK compilato da te con un'altra chiave non può aggiornare questo senza disinstallarlo prima
(le impostazioni si recuperano con il backup/importazione) e ha uno SHA-1 diverso.

## Compilare e installare

Requisiti: JDK 17 o superiore, Android SDK (o semplicemente Android Studio recente).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

oppure apri la cartella in Android Studio e premi *Run*. L'APK di debug è firmato con la chiave di
debug del tuo computer (`~/.android/debug.keystore`), il cui SHA-1 resta stabile.

### APK di release firmato

Crea una chiave (una volta sola) e un file `keystore.properties` nella radice del progetto (è
escluso da git):

```bash
keytool -genkeypair -v -keystore syncer-release.jks -alias syncer -keyalg RSA -keysize 4096 -validity 36500
```

```properties
storeFile=syncer-release.jks
storePassword=…
keyAlias=syncer
keyPassword=…
```

Poi `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

### GitHub Actions

Il workflow `.github/workflows/android.yml` esegue test e lint e pubblica gli APK come artefatto
(`syncer-apk`) a ogni push. L'APK di debug prodotto in CI ha una chiave diversa a ogni esecuzione;
per avere un APK con SHA-1 stabile aggiungi questi *secrets* al repository e usa l'APK di release:

| Secret | Valore |
|---|---|
| `SYNCER_KEYSTORE_BASE64` | `base64 -w0 syncer-release.jks` |
| `SYNCER_KEYSTORE_PASSWORD` | password del keystore |
| `SYNCER_KEY_ALIAS` | alias della chiave |
| `SYNCER_KEY_PASSWORD` | password della chiave |

## Formati prodotti

**Calendari (`<nome calendario>.ics`)** — un `VCALENDAR` per calendario con `X-WR-CALNAME`,
`VTIMEZONE` generati per ogni fuso usato, eventi con `UID` di Google (`<id>@google.com`),
ricorrenze (`RRULE`, `RDATE`, `EXDATE`), istanze modificate (`RECURRENCE-ID`), istanze annullate
come `EXDATE`, eventi di tutto il giorno come date, partecipanti, organizzatore, stato,
disponibilità, visibilità e promemoria (`VALARM`). Righe piegate a 75 byte, fine riga CRLF.
In *Impostazioni › Dati e cartelle › Calendari* trovi l'elenco dei calendari dell'account con il
loro stato sul telefono (sincronizzazione Android attiva o no, numero di eventi presenti): puoi
escluderne alcuni dall'esportazione e, per quelli con la sincronizzazione Android disattivata,
attivarla con *Attiva sincronizzazione* (Android scarica così gli eventi sul telefono). La
visibilità nell'app Calendar non conta: anche i calendari nascosti vengono esportati. Se un
calendario non ha eventi sul telefono, il suo file esportato in precedenza viene mantenuto.

**Attività (`<elenco>.todo.txt`)** — una riga per attività, prima quelle da fare (nell'ordine di
Google Tasks, con le sotto-attività subito dopo la principale), poi le completate:

```
Comprare il latte due:2024-01-05
Pagare la bolletta — entro venerdì / conto cointestato
x 2024-01-02 Prenotare il dentista
```

Le note dell'attività sono accodate dopo ` — ` su una sola riga.

**Note (`<titolo>.md`)** — front matter YAML (compatibile con Obsidian) e contenuto:

```markdown
---
title: "Spesa"
created: 2024-01-01T10:00:00Z
updated: 2024-01-02T11:00:00Z
tags:
  - "Casa"
pinned: true
source: google-keep
---

- [ ] Latte
- [x] Pane

## Allegati

![foto.jpg](attachments/foto.jpg)
```

Le note senza titolo prendono il nome dalla prima riga; i nomi duplicati ricevono ` (2)`, ` (3)`…

**Contatti (`Contatti.vcf`)** — tutti i contatti dell'account in un file vCard 3.0: nome
strutturato, soprannomi, telefoni ed e-mail con tipo (le etichette personalizzate come
`itemN.X-ABLabel`, lo stesso stile degli export di Google), indirizzi, organizzazione, siti,
compleanno e anniversari, relazioni, IM/SIP, note, etichette come `CATEGORIES` e, se attivo, la
foto (miniatura).

**Backup delle impostazioni (`syncer-settings_….json`)**:

```json
{
  "format": "syncer-settings",
  "version": 1,
  "createdAt": "2026-09-22T19:33:05Z",
  "appVersion": "1.0.0",
  "settings": { "accountName": "…", "calendar": { "enabled": true, "folderUri": "content://…" } }
}
```

I permessi di accesso alle cartelle non si trasferiscono tra installazioni o telefoni: dopo
un'importazione la configurazione guidata ti chiede di confermare le cartelle da riselezionare.

## Limiti noti

- Keep per account personali solo tramite export Takeout (vedi sopra).
- Google Tasks e Keep non notificano le modifiche: aggiornamento periodico, minimo 15 minuti.
- I calendari esportati contengono gli eventi presenti sul telefono, cioè quelli scaricati dalla
  sincronizzazione Google di Android (vedi l'elenco in *Impostazioni › Dati e cartelle › Calendari*).
- Le foto dei contatti sono le miniature salvate sul telefono.
- Esportazione a senso unico: modificare i file non modifica i dati su Google.

## Struttura del progetto

```
app/src/main/java/io/github/waph1/syncer/
├── format/     # scrittori puri: IcsWriter, VCardWriter, TodoTxtWriter, MarkdownWriter, Kdbx (KeePass)
├── source/     # lettura dati: CalendarContract, ContactsContract, Tasks API, Keep, password (CXF/CSV)
├── security/   # password del database KeePass nel Keystore Android
├── storage/    # cartelle SAF (SafFolder) e scrittura incrementale con manifest (ManagedFolder)
├── settings/   # AppSettings, persistenza, backup/import JSON
├── sync/       # SyncEngine, SyncWorker, SyncScheduler (WorkManager), PasswordVault, stato e notifiche
└── ui/         # Compose: configurazione guidata, home, impostazioni
```

## Test

```bash
./gradlew testDebugUnitTest lintDebug
```

Oltre ai test dei singoli formati, i test Robolectric eseguono l'intero flusso con provider di
calendario, contatti e cartelle simulati (esportazione, aggiornamento solo se cambiato,
eliminazione dei file obsoleti, Takeout → Markdown, backup con timestamp) e percorrono l'interfaccia
dalla configurazione guidata fino alla schermata principale.
