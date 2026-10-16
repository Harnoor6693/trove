# plugins/ — collapse inventory

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild plugin modules, since removed. Current state: `docs-site/guide/architecture.md`.

All paths repo-relative from the repository root. Verified against source on
2026-09-03 (commit `9ad1b5f`). Every defect cited in §10/§M0 below was re-checked directly against
the file:line shown, not carried over from `docs/recovery/01-FINDINGS.md` without re-verification —
that file's §O supersedes its own earlier sections and is treated as authoritative where the two
disagree; no disagreements were found. Read-only pass: no source under `plugins/`, `services/`,
or `libs/` was modified. LOC via `find <dir> -name '*.java' | xargs wc -l | tail -1`.

## 0. Roster and disposition

| # | Module (artifactId) | Package | LOC main/test | Depended on by | Disposition |
|---|---|---|---|---|---|
| 1 | `plugin-bank-simplefin` | `finance.trove.plugin.simplefin` | 437 / 230 | `ingestion-service` | **KEEP** → `finance.trove.ingestion.simplefin` |
| 2 | `plugin-bank-ofx` | `finance.trove.plugin.ofx` | 715 / 629 | *(none)* | **KEEP** → `finance.trove.ingestion.ofx` |
| 3 | `plugin-bank-imap` | `finance.trove.plugin.imap` | 789 / 512 | *(none)* | **CUT** (IMAP ingestion cut) |
| 4 | `plugin-bank-emails` | `finance.trove.plugin.bankemails` | 626 / 571 | *(none)* | **CUT** (bank-email ingestion cut) |
| 5 | `plugin-model-ollama` | `finance.trove.model.ollama` | 527 / 184 | *(none)* | **CUT** (replaced by generic OpenAI-compat client) |
| 6 | `plugin-model-litellm` | `finance.trove.model.litellm` | 548 / 227 | *(none)* | **CUT** (replaced by generic OpenAI-compat client) |
| 7 | `plugin-model-paddleocr` | `finance.trove.model.paddleocr` | 467 / 198 | `receipt-processor` | **CUT** (PaddleOCR cut per owner decision) |
| | **Total** | | **4109 / 2551** | | matches `01-FINDINGS.md`'s "4,109 plugins" LOC exactly |

Verification of the "which service depends on which plugin" column
(`grep -n "plugin-bank-\|plugin-model-" services/*/pom.xml`):

```
services/receipt-processor/pom.xml:31:    <artifactId>plugin-model-paddleocr</artifactId>
services/ingestion-service/pom.xml:23:   <artifactId>plugin-bank-simplefin</artifactId>
```

No other `services/*/pom.xml` references any `plugin-bank-*` or `plugin-model-*` artifact. This
confirms `01-FINDINGS.md` §M3 exactly: **5 of 7 plugins are on no service's classpath at all** —
`ofx` included, despite being one of the two KEEP modules; it ships fully built, tested and
JaCoCo'd, but no `services/*/pom.xml` ever pulls it in. OFX file-upload ingestion is dead code
today in exactly the same sense as the five CUT plugins — the difference is only that the owner
wants it *revived* as a package, not deleted.

## 1. Loading mechanism (applies to all 7 identically)

`01-FINDINGS.md` §M4 ("there is no plugin architecture") is confirmed. Every module:

- Registers via the **Spring Boot 3+ auto-configuration file**
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  a one-line file naming its `@AutoConfiguration` class (verified for all 7 — see per-plugin
  sections). This is a real, working Spring mechanism, but it activates purely because the JAR is
  **on the classpath** (a `pom.xml` `<dependency>`), gated only by a `@ConditionalOnProperty`.
- `ServiceLoader` / `META-INF/services` / `URLClassLoader` / `spring.factories`: **zero hits**
  repo-wide (re-confirmed: `grep -rl "ServiceLoader\|META-INF/services" plugins/` → empty). There
  is no runtime plugin discovery, no plugin registry that enumerates jars, no hot-loading. "Plugin"
  means "ordinary compile-time Maven dependency, activated by a Spring Boot auto-config file."
- Each bank-source module's beans terminate in a class `implements BankSourcePlugin`
  (`libs/common/src/main/java/finance/trove/common/plugin/BankSourcePlugin.java:22`); each model
  module's beans terminate in a class `implements ModelAdapter`
  (`libs/model-adapter-api/src/main/java/finance/trove/model/adapter/ModelAdapter.java:22`).
  Nothing in `libs/common` or anywhere else scans for these interfaces at runtime — a
  `PluginRegistry`/routing-registry that "discovers" them via component scan is asserted in
  javadoc (e.g. `BankSourcePlugin.java:11-12`, `ModelAdapter.java:16`) but **no such registry class
  exists in the repo** (`grep -rl "PluginRegistry" .` → zero hits outside that one javadoc
  sentence, repeated verbatim in each `*AutoConfiguration.java`). A consuming service that
  `@Autowired`s `List<BankSourcePlugin>` would get however many plugin JARs happen to be on its own
  classpath — for `ingestion-service` today that is exactly one, `SimplefinPlugin`, since `ofx` is
  not a dependency (see §0).

## 2. The two SPIs

### 2.1 `BankSourcePlugin` (`libs/common/src/main/java/finance/trove/common/plugin/BankSourcePlugin.java`)

```java
public interface BankSourcePlugin {
    String getId();                     // stable key, e.g. "simplefin"
    String getDisplayName();
    String getDescription();
    void testConnection(Map<String,String> credentials) throws BankSourceConnectionException;
    List<RawTransaction> fetchTransactions(String accountId, LocalDate from, LocalDate to,
                                            Map<String,String> credentials) throws BankSourceConnectionException;
}
```

Every implementation returns `List<RawTransaction>`
(`libs/common/src/main/java/finance/trove/common/plugin/RawTransaction.java:23-36`), a record with
field order:

```java
record RawTransaction(String id, String sourceId, String accountId, BigDecimal amount,
    String currency, String description, String merchantName, LocalDate date, Instant postedAt,
    TransactionType type, boolean pending, Map<String,String> metadata)
```

`sourceId` is read by `DeduplicationService.deduplicate`
(`services/ingestion-service/src/main/java/finance/trove/ingestion/dedup/DeduplicationService.java:63-66`)
for an exact-match lookup against `TransactionRepository.findByAccountIdAndSourceId(accountId,
incoming.sourceId())`, backed by the unique index
`services/ingestion-service/src/main/resources/db/migration/V2__transaction.sql:23`:
`CREATE UNIQUE INDEX idx_transaction_source ON transaction(account_id, source_id) WHERE source_id
IS NOT NULL`. `incoming.id()` is **read nowhere** in `services/ingestion-service` — confirmed by
`grep -rn "\.id()" services/ingestion-service/src/main/java/finance/trove/ingestion/dedup/` →
zero hits. This is the M0 defect; see §3.3.

### 2.2 `ModelAdapter` (`libs/model-adapter-api/src/main/java/finance/trove/model/adapter/ModelAdapter.java`)

```java
public interface ModelAdapter {
    String getId();  String getDisplayName();  Set<ModelCapability> getCapabilities();
    List<ModelInfo> listAvailableModels();
    CompletionResponse complete(CompletionRequest request);
    EmbeddingResponse embed(EmbeddingRequest request);
    OcrResult ocr(byte[] imageBytes, String mimeType);
    boolean isHealthy();
}
```

`ModelCapability` is `TEXT_COMPLETION | EMBEDDINGS | OCR`
(`libs/model-adapter-api/.../ModelCapability.java:12-18`). `CompletionRequest`/`EmbeddingRequest`
are validated records (non-null model/messages/input, `maxTokens>0`) carrying an opaque
`requestId` for tracing; `OcrResult` validates `confidence∈[0,1]` and carries a list of
`BoundingBox` regions. This SPI is consumed only by `model-manager`'s routing layer per
`01-FINDINGS.md` §M6 — model-manager itself has **no authentication** (§M1) so the whole surface is
moot in production today regardless of which adapters are wired.

---

## 3. `plugin-bank-simplefin` — KEEP

**Purpose.** Client for the SimpleFIN Bridge open-banking protocol (a lightweight, user-consented
read API used mostly by North American banks/aggregators). One HTTP call fetches every linked
account and its full transaction list; the plugin filters client-side to the requested account and
date window.

**LOC.** main 437, test 230 (7 main classes + 3 dto records; 3 test classes).

**Files:**
- `plugins/bank-sources/simplefin/src/main/java/finance/trove/plugin/simplefin/SimplefinPlugin.java` — `BankSourcePlugin` impl, `getId()="simplefin"` (line 53)
- `.../SimplefinClient.java` — HTTP transport
- `.../SimplefinNormalizer.java` — DTO → `RawTransaction` mapping
- `.../SimplefinProperties.java` — `@ConfigurationProperties("trove.simplefin")`
- `.../SimplefinAutoConfiguration.java` — Spring wiring
- `.../dto/{SimplefinAccount,SimplefinAccountSet,SimplefinTransaction}.java`

**SPI implemented.** `BankSourcePlugin` (§2.1). `getId()="simplefin"`, `getDisplayName()="SimpleFIN
Bridge"` (`SimplefinPlugin.java:53,56`).

**Loading.** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` →
`finance.trove.plugin.simplefin.SimplefinAutoConfiguration`. Activated by
`@ConditionalOnProperty(value="trove.simplefin.enabled", matchIfMissing=true)` — on by default once
the JAR is a dependency. Three `@ConditionalOnMissingBean` beans: `SimplefinClient`,
`SimplefinNormalizer`, `SimplefinPlugin` (`SimplefinAutoConfiguration.java:40-56`).

**Depended on by.** `services/ingestion-service/pom.xml:23` (`plugin-bank-simplefin`) — the **only**
plugin-service dependency for bank-sources.

**Config keys** (prefix `trove.simplefin`, `SimplefinProperties.java`):
| Key | Default | Used by |
|---|---|---|
| `trove.simplefin.enabled` | `true` | gates the whole `AutoConfiguration` |
| `trove.simplefin.timeout-seconds` | `30` | read timeout message text only (see below) |
| `trove.simplefin.connect-timeout-seconds` | `5` | declared, **not wired into the actual `RestClient`** — `SimplefinClient.fetchAccountSet` (line 58) builds `RestClient.builder().baseUrl(accessUrl).build()` with no `ClientHttpRequestFactory`/timeout configured; `timeoutSeconds` is used only to compose the exception message on `ResourceAccessException` (lines 70-72), not to actually bound the call. Carry this forward as a defect to fix in the merge, not a design to copy verbatim.

There is no `trove.simplefin.base-url` — the base URL is **per-user, per-connection data**
(`credentials.get("accessUrl")`), not a static config key; see bridge protocol below.

**Outbound hosts.** None static. Every call targets the **user-supplied SimpleFIN Bridge access
URL** stored (encrypted) per connected account. The one-time setup-token exchange also POSTs to a
**user-decoded URL** — see SSRF note below.

**Tables.** None owned by this plugin. It only produces `RawTransaction` records consumed by
`ingestion-service`'s `transaction` table (owned by that service, documented in
`docs/recovery/inventory/ingestion-service.md`).

### 3.1 SimpleFIN bridge protocol, as actually implemented

Two separate legs, in two separate modules today:

1. **Setup-token exchange** (NOT in this plugin — lives in `ingestion-service`):
   `services/ingestion-service/src/main/java/finance/trove/ingestion/account/HttpSetupTokenExchanger.java:27-50`.
   SimpleFIN setup tokens are base64 strings that decode to a one-time-use URL. The exchanger
   issues `POST <decodedSetupUrl>` with `Content-Length: 0` and an empty body; the response body
   **is** the permanent access URL (which embeds `https://user:pass@bridge.example.com/...`
   credentials). 403/410 → `SETUP_TOKEN_ALREADY_EXCHANGED`; other 4xx →
   `SETUP_TOKEN_INVALID`; 5xx/network error → `BRIDGE_UNREACHABLE`
   (`HttpSetupTokenExchanger.java:34-43`). **This is `01-FINDINGS.md` §G2's SSRF primitive** —
   `ExchangeSetupTokenService` validates the decoded URL only with `startsWith("http")`, no host/IP
   allowlist, and the exchanged access URL is then persisted encrypted, giving a *stored* SSRF
   target reachable on every subsequent sync. Carry the fix (scheme + host/IP-range allowlist) into
   the merge; it is orthogonal to the plugin code itself but must move with it since the target
   package will own both halves.
2. **Data fetch** (this plugin): `SimplefinClient.fetchAccountSet(accessUrl)`
   (`SimplefinClient.java:56-77`) issues `GET {accessUrl}/accounts` — the *only* endpoint SimpleFIN
   defines. There is **no server-side date-range parameter sent** — the SimpleFIN protocol itself
   supports optional `start-date`/`end-date` query params on `/accounts`, but this client does not
   set them; it fetches the *entire* transaction history the bridge is willing to return every
   sync, then filters in memory:
   `SimplefinPlugin.fetchTransactions` (lines 106-125) converts `[from, to]` to a UTC
   `[fromInstant, toInstant)` window and does
   `account.transactions().stream().filter(tx -> tx.transacted() != null && ...)`. This is a real
   inefficiency to flag for the merge (every sync re-downloads the full history) but not a
   correctness bug — the date filter itself is correct.
   Response shape: `SimplefinAccountSet{accounts: [SimplefinAccount{id, name, currency, balance,
   balance-date, transactions:[SimplefinTransaction{id, amount, description, transacted, posted,
   extra}]}]}` (`dto/*.java`), all `@JsonIgnoreProperties(ignoreUnknown=true)`. Amount sign carries
   the CREDIT/DEBIT direction (positive=CREDIT); currency is read from the **account**, not each
   transaction (SimpleFIN's model is one currency per account).

### 3.2 Normalizer code path

`SimplefinNormalizer.normalize(SimplefinAccount, SimplefinTransaction)`
(`plugins/bank-sources/simplefin/src/main/java/finance/trove/plugin/simplefin/SimplefinNormalizer.java:49-68`):
amount → `abs()` + derived `TransactionType`; date → `tx.transacted()` in UTC, falling back to
`LocalDate.now(UTC)` if null; `pending` → `tx.posted()==null`; extra fields preserved under
metadata key `simplefin_extra`.

### 3.3 M0 defect in this normalizer (verified)

`SimplefinNormalizer.java:51-54`:
```java
return new RawTransaction(
        tx.id(),        // line 52 → RawTransaction.id    (the ONE genuinely unique field)
        "simplefin",     // line 53 → RawTransaction.sourceId (CONSTANT, same for every tx)
        account.id(),    // line 54 → RawTransaction.accountId
        ...
```
Matches `01-FINDINGS.md` M0 exactly (`SimplefinNormalizer.java:53 -> "simplefin"`). Because
`DeduplicationService` step 1 matches on `(accountId, sourceId)` and `sourceId` is the literal
string `"simplefin"` for every transaction from every account, the **second** transaction ever
synced for a given account collides with the first (`findByAccountIdAndSourceId` returns the first
row), and is treated as an update-or-skip, never a second insert. **At most one transaction per
account can ever exist via this plugin.** Fix (per `01-FINDINGS.md` §O7): swap which field carries
which value — `tx.id()` (the provider transaction id) belongs in `sourceId`; a plugin-constant or
nothing belongs in `id`. Rename `sourceId` → `providerTransactionId` while merging, per the
verification's O7 recommendation, so the two readings ("id of the source" vs "id from the source")
cannot recur.

---

## 4. `plugin-bank-ofx` — KEEP

**Purpose.** Parses user-uploaded OFX/QFX bank-statement export files (both OFX 1.x SGML and OFX
2.x XML) from a sandboxed local upload directory into `RawTransaction`s. No network calls — pure
file I/O + parsing.

**LOC.** main 715, test 629 (6 main classes + 3 dto records; 6 test classes — the most heavily
tested plugin of the seven).

**Files:**
- `plugins/bank-sources/ofx/src/main/java/finance/trove/plugin/ofx/OfxPlugin.java` — `BankSourcePlugin` impl, sandbox enforcement
- `.../OfxParser.java` — entry point: 50 MB size guard, reads full stream, detects format from first 512 bytes, dispatches
- `.../OfxFormatDetector.java` — SGML-vs-XML sniffing
- `.../OfxSgmlParser.java` — OFX 1.x: regex/substring extraction (not valid XML, no DOM parser applies)
- `.../OfxXmlParser.java` — OFX 2.x: `DocumentBuilderFactory` with external entities and DOCTYPE disabled (XXE-hardened)
- `.../OfxNormalizer.java` — DTO → `RawTransaction`
- `.../OfxAutoConfiguration.java`
- `.../dto/{OfxAccount,OfxDocument,OfxTransaction}.java`

**SPI implemented.** `BankSourcePlugin`. `getId()="ofx"`, `getDisplayName()="OFX / QFX File Import"`
(`OfxPlugin.java:62,67`).

**Loading.** `.../META-INF/spring/....imports` → `finance.trove.plugin.ofx.OfxAutoConfiguration`,
`@ConditionalOnProperty(value="trove.ofx.enabled", matchIfMissing=true)`. Single bean `OfxPlugin`
(`@ConditionalOnMissingBean`), constructed with `new OfxParser()` / `new OfxNormalizer()` internally
(not Spring beans themselves — `OfxPlugin.java:44-45`).

**Depended on by.** **Nobody.** `grep -rn "plugin-bank-ofx" services/*/pom.xml` → zero hits. Fully
built, checkstyled, and tested, but wired into no service — this is the one KEEP module that is
*also* one of the M3 "unconsumed" five in practice today.

**Config keys.** `trove.ofx.enabled` (default `true`) is the only property; `OfxPlugin`'s upload
sandbox base path is a **hardcoded constant**, not a config key:
`DEFAULT_UPLOAD_BASE = Path.of("/var/trove/uploads")` (`OfxPlugin.java:41`), overridable only via
the package-private test constructor. Flag for the merge: make this a
`@ConfigurationProperties` value (e.g. `trove.ofx.upload-dir`) so the collapsed core's
receipt/upload storage layout (local-disk volume, per the owner's MinIO-drop decision) can be
configured consistently in one place.

**Outbound hosts.** None — local filesystem only.

**Tables.** None owned by this plugin.

### 4.1 Parser capabilities

- **Formats:** OFX 1.x SGML (flat tags, no closing tags, preceded by an `OFXHEADER:` block) and OFX
  2.x XML (well-formed, XML-declaration or bare `<OFX>` root). Detection
  (`OfxFormatDetector.detect`, lines 43-53) is a case-insensitive ASCII heuristic over the first 512
  bytes: `<?xml` or `<ofx>` without `ofxheader:` → XML; `ofxheader:` or `data:ofxsgml` → SGML;
  neither → `BankSourceConnectionException("Unrecognised file format")`.
- **Encodings:** the SGML path decodes the **entire byte buffer as UTF-8 unconditionally**
  (`OfxParser.java:56`: `new String(bytes, StandardCharsets.UTF_8)`) — it does **not** read the
  OFX 1.x header's own `CHARSET:`/`ENCODING:` fields (e.g. many real-world OFX1 exports declare
  `1252` (Windows-1252) or `ISO-8859-1`). A file in either of those encodings containing non-ASCII
  merchant names (e.g. accented characters) will be silently mis-decoded, not rejected. The XML
  path (`OfxXmlParser.parse(byte[])`) hands raw bytes to `DocumentBuilder`, which honours the XML
  declaration's `encoding=` attribute correctly per the XML spec — so **XML files' encodings are
  respected; SGML files' are not.** Flag as a known gap to fix or explicitly scope out during the
  merge, since OFX1 exports from older institutions are the more likely real-world case.
- **Size / count limits:** 50 MB per file (`OfxParser.MAX_FILE_SIZE = 52_428_800L`), 10,000
  transactions per file in both parsers (`OfxSgmlParser.MAX_TRANSACTIONS` /
  `OfxXmlParser.MAX_TRANSACTIONS`), both hard `throw` (not truncate) past the transaction cap.
- **Required fields:** `ACCTID` required at account level (XML path throws if absent via
  `getText(doc,"ACCTID")`; SGML path defaults to `"unknown"` — an inconsistency between the two
  parsers worth normalising during the merge). Per-transaction `FITID`/`TRNAMT` missing → the
  transaction is silently skipped (both parsers), not an error.
- **Date parsing:** OFX `yyyyMMdd`, only the first 8 characters read (so `yyyyMMddHHmmss[.xxx]`
  variants with a time component parse fine); no timezone offset handling since OFX date-time
  suffixes are dropped, not honoured.
- **Sandbox path safety:** `OfxPlugin.testConnection`/`fetchTransactions` both resolve
  `uploadBase.resolve(filePath).normalize()` and reject anything that doesn't
  `path.startsWith(uploadBase)` (lines 93-101, 134-142) — real, working path-traversal protection.

### 4.2 Normalizer code path

`OfxNormalizer.normalize(OfxAccount, OfxTransaction)`
(`plugins/bank-sources/ofx/src/main/java/finance/trove/plugin/ofx/OfxNormalizer.java:53-86`):
type from amount sign; date = `dateUser` if present else `datePosted`; description = `MEMO` else
`NAME` else `""`; `pending` is **always `false`** (OFX statements only ever describe settled
history); `TRNTYPE`/`CHECKNUM` preserved in metadata. **Currency is hardcoded** —
`DEFAULT_CURRENCY = "USD"` (line 32) is used unconditionally; OFX does carry a `CURDEF`/`CURSYM`
field in the real protocol, but neither `OfxDocument`/`OfxAccount` captures it nor does the
normalizer read it. This will corrupt CAD-account OFX imports under the owner's CAD/USD
per-user-currency decision and must be fixed as part of the merge (thread `CURDEF` through the DTO
and the normalizer, or take the owner's connected-account currency as the source of truth instead).

### 4.3 M0 defect in this normalizer (verified)

`OfxNormalizer.java:31,72-75`:
```java
private static final String SOURCE_ID = "ofx";       // line 31
...
return new RawTransaction(
        tx.fitId(),   // line 73 → RawTransaction.id        (the genuinely unique field: OFX FITID)
        SOURCE_ID,    // line 74 → RawTransaction.sourceId   (CONSTANT "ofx" for every tx, every account)
        account.accountId(),  // line 75
```
Matches `01-FINDINGS.md` M0 exactly (`OfxNormalizer.java:74 -> SOURCE_ID (a constant)`). Same
consequence as §3.3: at most one transaction per account can ever exist once this plugin is wired
to a live dedup pass, because every OFX-imported transaction for a given account shares the literal
`sourceId="ofx"`. Same fix: put `tx.fitId()` in the field that `DeduplicationService` actually
matches on.

---

## 5. Bank-source normalizer defect — completeness check across all four

The task asks for the exact M0 defect in **every** bank normalizer, including the two that are
being cut, for a complete before/after record. All four are now verified directly:

| Normalizer | File:line | `id` (should be per-tx unique) | `sourceId` (should be the plugin's own dedup key, but ends up being the actual bug slot) |
|---|---|---|---|
| SimpleFIN | `plugins/bank-sources/simplefin/src/main/java/finance/trove/plugin/simplefin/SimplefinNormalizer.java:52-53` | `tx.id()` (correct — genuinely unique) | `"simplefin"` (constant) |
| OFX | `plugins/bank-sources/ofx/src/main/java/finance/trove/plugin/ofx/OfxNormalizer.java:73-74` | `tx.fitId()` (correct — genuinely unique) | `SOURCE_ID` = `"ofx"` (constant) |
| IMAP (cut) | `plugins/bank-sources/imap/src/main/java/finance/trove/plugin/imap/ImapNormalizer.java:35-36` | `emailMessageId` (correct — genuinely unique) | `"imap"` (constant) |
| bank-emails (cut) | `plugins/bank-sources/bank-emails/src/main/java/finance/trove/plugin/bankemails/BankEmailPlugin.java:116-117` | `UUID.randomUUID().toString()` (**wrong even as the "unique" field** — a fresh random UUID every fetch, not the provider's own identifier, so it could never be used for dedup even if `DeduplicationService` read `id`) | `"bank-email"` (constant) |

`01-FINDINGS.md`'s M0 text says "ALL FOUR bank normalizers pass a CONSTANT PLUGIN NAME into the
sourceId slot" but only cites three file:lines. The fourth is confirmed above: bank-emails has no
separate `*Normalizer` class — `BankEmailPlugin.fetchTransactions`
(`BankEmailPlugin.java:95-130`) constructs the `RawTransaction` inline, and it is actually the
**worst** of the four, since even its `id` field is disposable. This module is being cut regardless
(§7), but the pattern is worth carrying forward as a lesson: every one of the four independently-
written normalizers made the identical field-order mistake, which is exactly the
"ambiguous name, no seam ever tested it" framing in `01-FINDINGS.md` §O7 — evidence for adopting
the `providerTransactionId` rename as a forcing function, not merely a docstring fix.

---

## 6. The 5 CUT plugins

### 6.1 `plugin-bank-imap` — CUT (IMAP ingestion cut)

**Purpose.** Connects to a user's IMAP mailbox, fetches recent messages, and asks an LLM (via
`AiGatewayClient`) to classify each one as a bank transaction notification and extract its fields.

**LOC.** main 789, test 512 — the largest plugin module.

**SPI.** `BankSourcePlugin` (`ImapPlugin.java:45`). `getId()` not read in this pass (module is
being deleted) but present per interface contract.

**Loading.** `.../META-INF/spring/....imports` →
`finance.trove.plugin.imap.ImapAutoConfiguration`, gated by
`@ConditionalOnProperty(name="trove.imap.enabled", havingValue="true")` — **opt-in, no
`matchIfMissing`**, unlike simplefin/ofx/bank-emails/litellm/ollama which default to enabled. Four
infrastructure beans (`ImapConnectionFactory`, `ImapMessageFetcher`, `EmailPreprocessor`,
`ImapNormalizer`) register unconditionally when the property is set; the two functional beans
(`EmailTransactionExtractor`, `ImapPlugin`) additionally require `@ConditionalOnBean(AiGatewayClient.class)`
(`ImapAutoConfiguration.java:101-126`) — so even fully enabled, this plugin is inert without an
`ai-gateway-client` bean present, which only three services provide transitively
(`01-FINDINGS.md` §O1).

**Config keys** (`ImapConfig.from`, `plugins/bank-sources/imap/src/main/java/finance/trove/plugin/imap/dto/ImapConfig.java:39-52`,
all supplied per-connection via the credentials map, not static properties): `host` (required),
`username` (required), `password`, `port` (default `993`), `useSsl` (default `true`),
`useStartTls` (default `false`), `useOAuth2` (default `false`), `oauthToken`, `folderName`
(default `"INBOX"`), `bankSenderPattern` (default `".*"`).

**Outbound hosts.** User-supplied IMAP host:port, plus whatever `AiGatewayClient`'s configured
model endpoint is (LOCAL_FAST alias, `PrivacyTier.HIGH` —
`EmailTransactionExtractor.java:102-103`).

**Tables.** None owned.

**Confirmed unreferenced outside `plugins/`:**
`grep -rl "finance.trove.plugin.imap" --include="*.java" --include="*.xml" --include="*.yml" .` →
every hit is inside `plugins/bank-sources/imap/` itself (its own main + test sources). Zero
references from `services/`, `libs/`, `apps/`, `infra/`, or `openapi/`.

**Notable defect (M9, re-verified):** `bankSenderPattern` is parsed into `ImapConfig` (line 28,
default `.*`) but **never applied as a filter** — `ImapMessageFetcher` retrieves every message in
the folder regardless
(`grep -n "bankSenderPattern" plugins/bank-sources/imap/src/main/java/**/*.java` → the only 3 hits
are the `ImapConfig` record field, its javadoc, and its `.from()` default; zero hits in
`ImapMessageFetcher.java` or `EmailPreprocessor.java`). Every email body in the mailbox — not just
bank notifications — is truncated to 4,000 chars and spliced verbatim into an LLM prompt. Moot for
the collapse since this plugin is being deleted, but worth noting as *why* it's being deleted, not
merely "IMAP ingestion is out of scope": the implementation as it stands is a privacy hazard on top
of being unused.

### 6.2 `plugin-bank-emails` — CUT (bank-email ingestion cut)

**Purpose.** Regex-template-based parser for bank transaction-notification emails (distinct from
`imap` — this plugin takes pre-supplied email text via the credentials map; it does not connect to
a mailbox itself). Templates are bank-specific regex sets loaded from a YAML resource.

**LOC.** main 626, test 571.

**SPI.** `BankSourcePlugin` (`BankEmailPlugin.java:35`).

**Loading.** `.../META-INF/spring/....imports` →
`finance.trove.plugin.bankemails.BankEmailAutoConfiguration`,
`@ConditionalOnProperty(value="trove.bank-email.enabled", matchIfMissing=true)`. Three
`@ConditionalOnMissingBean` beans: `BankEmailTemplateRegistry`, `BankEmailParser`,
`BankEmailPlugin`.

**Config keys.** `trove.bank-email.enabled` (default `true`). Templates loaded once from the
classpath resource `/bank-email-templates.yml`
(`BankEmailTemplateRegistry.java:38`, via Jackson `YAMLFactory`) — not an externalised config key,
a bundled resource; if missing/unparseable the registry starts empty rather than failing (per its
own javadoc, lines 29-31), so a bad or absent template file degrades silently to "this plugin never
matches anything."

**Outbound hosts.** None — pure text parsing, no network calls.

**Tables.** None owned.

**Confirmed unreferenced outside `plugins/`:** same grep pattern as §6.1, all 14 hits (7 main + 7
test files) are within `plugins/bank-sources/bank-emails/`.

**Notable engineering, worth salvaging as a pattern even though the module is cut:**
`ReDoSGuard` (`plugins/bank-sources/bank-emails/src/main/java/finance/trove/plugin/bankemails/ReDoSGuard.java`)
runs every regex match/find on a bounded daemon-thread executor with a wall-clock
`timeoutMs`, returning `false`/`Optional.empty()` on timeout rather than blocking the caller
indefinitely — genuine ReDoS mitigation for untrusted template/user input. If any regex-matching
survives the collapse (e.g. CSV-header sniffing in the new import path, §9), this pattern is worth
copying into `trove-core` rather than re-inventing.

### 6.3 `plugin-model-ollama` — CUT (replaced by generic OpenAI-compatible client)

**Purpose.** HTTP adapter for a local Ollama daemon using **Ollama's native REST API**
(`/api/chat`, `/api/embed`, `/api/tags` — confirmed via DTO names `OllamaChatRequest`,
`OllamaEmbedRequest`, `OllamaTagsResponse`), not the OpenAI-compatible surface Ollama also exposes.

**LOC.** main 527, test 184.

**SPI.** `ModelAdapter` (`OllamaAdapter.java:52`).

**Loading.** `.../META-INF/spring/....imports` → `finance.trove.model.ollama.OllamaAutoConfiguration`,
`@ConditionalOnProperty(name="trove.ollama.enabled", matchIfMissing=true)`. Two beans:
`OllamaClient`, `OllamaAdapter`.

**Config keys** (`OllamaProperties.java`, prefix `trove.ollama`): `base-url` (default
`http://ollama:11434`), `timeout-seconds` (default `120`), `connect-timeout-seconds` (default `5`),
`enabled` (default `true`).

**Outbound hosts.** `http://ollama:11434` by default — a fixed Docker-network hostname, not a
per-user setting. No `Authorization` header is sent (`OllamaClient.java:22-23`: "No authentication
header is required because Ollama is accessed only within the private Docker network" — an
assumption that does not survive the owner's public-internet exposure decision anyway).

**Tables.** None owned.

**Confirmed unreferenced outside `plugins/`:** all 12 hits for `finance.trove.model.ollama` are
within `plugins/models/ollama-adapter/`.

**Why cut, not kept as-is:** the owner's target is "a user-configured **OpenAI-compatible**
endpoint (Ollama or cloud)". This adapter speaks Ollama's *native* wire format, not the
OpenAI-compatible one Ollama also serves at `/v1/chat/completions` — so keeping it verbatim would
mean maintaining a second, non-standard client just for the local-model case instead of one client
that works identically against Ollama, LiteLLM, or any cloud OpenAI-compatible provider. Cutting it
is the right call structurally, not just a scope trim.

### 6.4 `plugin-model-litellm` — CUT (replaced by generic OpenAI-compatible client)

**Purpose.** HTTP adapter for a LiteLLM proxy, speaking the **OpenAI Chat Completions wire format**
directly (`ChatCompletionRequest`/`ChatCompletionResponse` DTOs map 1:1 to `POST
/v1/chat/completions` — confirmed via `ChatCompletionRequest.java:8-21` javadoc and field shape:
`model`, `messages: List<Map<String,String>>`, `max_tokens`, `temperature`, `stream`).

**LOC.** main 548, test 227.

**SPI.** `ModelAdapter` (`LiteLlmAdapter.java:50`).

**Loading.** `.../META-INF/spring/....imports` →
`finance.trove.model.litellm.LiteLlmAutoConfiguration`,
`@ConditionalOnProperty(name="trove.litellm.enabled", matchIfMissing=true)`. Two beans:
`LiteLlmClient`, `LiteLlmAdapter`.

**Config keys** (`LiteLlmProperties.java`, prefix `trove.litellm`): `base-url` (default
`http://litellm:4000`), `api-key` (default the **placeholder** `sk-local`, sent as `Authorization:
Bearer …`), `timeout-seconds` (default `60`), `connect-timeout-seconds` (default `5`), `enabled`
(default `true`).

**Outbound hosts.** `http://litellm:4000` by default — again a fixed Docker-network hostname
assuming a co-located LiteLLM proxy container, not a user-supplied external endpoint.

**Tables.** None owned.

**Confirmed unreferenced outside `plugins/`:** all 12 hits for `finance.trove.model.litellm` are
within `plugins/models/litellm-adapter/`.

**Why cut, but salvage the DTO shapes:** unlike Ollama's adapter, this one is **already** speaking
the exact wire format the target design needs (§8) — it is functionally the closest existing code
to "a generic OpenAI-compatible client." It is still being cut as a *module* because (a) the
`ModelAdapter`/`ModelCapability`/model-manager routing abstraction it plugs into is itself being
collapsed away (no separate model-manager service, no adapter registry — see §8), and (b) its
config model (fixed default host pointing at a co-located `litellm` container, single global
enable flag) is the opposite of the owner's decision (per-user-configured endpoint, off until
configured, no privileged default host). Its request/response DTOs
(`plugins/models/litellm-adapter/src/main/java/finance/trove/model/litellm/dto/*.java`) are still
the right starting point to copy into the new `finance.trove.ai` client rather than writing OpenAI
DTOs from scratch.

### 6.5 `plugin-model-paddleocr` — CUT (PaddleOCR cut per owner decision)

**Purpose.** HTTP adapter for a PaddleOCR hub-serving instance, for receipt-image OCR.

**LOC.** main 467, test 198.

**SPI.** `ModelAdapter` (`PaddleOcrAdapter.java:53`).

**Loading.** `.../META-INF/spring/....imports` →
`finance.trove.model.paddleocr.PaddleOcrAutoConfiguration`,
`@ConditionalOnProperty(name="trove.paddleocr.enabled", havingValue="true")` — **no
`matchIfMissing`**, so unlike ollama/litellm this one defaults OFF even when the JAR is present
(`PaddleOcrProperties.enabled` default `false`, `PaddleOcrAutoConfiguration.java:41-42`).

**Config keys** (`PaddleOcrProperties.java`, prefix `trove.paddleocr`): `base-url` (default
`http://paddleocr:8866`), `timeout-seconds` (default `60`), `connect-timeout-seconds` (default
`5`), `enabled` (default **`false`**), `max-file-size-bytes` (default `20971520` / 20 MB).

**Depended on by.** `services/receipt-processor/pom.xml:31` — the **only** consumed model plugin.
This is the one entry in the "5 CUT" set that is *not* dead code today; it is real, wired,
compiling client code — see below for why it still does nothing in production.

**Outbound hosts.** `http://paddleocr:8866` by default.

**Tables.** None owned by this plugin (line items would be persisted by `receipt-processor` itself,
not the adapter — and per `01-FINDINGS.md` §M8, are never reached in practice: OCR score ceiling
without a matched transaction is below the review threshold, so `persistLineItems()` is never
called on the only path this adapter's output can currently take).

**Confirmed unreferenced outside `plugins/` other than the one consumer:**
`grep -rl "finance.trove.model.paddleocr"` → hits inside
`plugins/models/paddleocr-adapter/` (its own sources) plus `services/receipt-processor/` (the
consumer, expected and already accounted for above — this is the pom.xml dependency, not a stray
reference).

**Why cut despite being the one CUT plugin with a real consumer:** `01-FINDINGS.md` §M8 (re-read,
not re-verified in this pass since it's outside `plugins/` — the receipt-processor client code and
its target are the finding, not this adapter's own logic): no `paddleocr` service exists in any
compose file, and the wire protocol this client speaks
(`{"results":[{"text","confidence","box"}]}`) is invented — real PaddleOCR hub-serving takes
base64 `images` and returns a differently-shaped `results[][]`. Only a hand-written WireMock stub
by the same author ever validated it. The owner's decision replaces PaddleOCR outright with the
same user-configured vision-capable OpenAI-compatible endpoint used for chat/categorisation
(receipts degrade to attach-image + manual fields when AI is off). This module's `ModelAdapter`
DTOs are not worth salvaging the way LiteLLM's are — the wire format doesn't match any real
service, local or cloud.

---

## 7. Confirmation: nothing outside `plugins/` references the 5 CUT plugins' classes

Repo-wide grep by fully-qualified package, across `*.java`, `*.xml`, `*.yml`, `*.yaml`:

```
finance.trove.plugin.imap      → only plugins/bank-sources/imap/**            (0 external hits)
finance.trove.plugin.bankemails→ only plugins/bank-sources/bank-emails/**     (0 external hits)
finance.trove.model.ollama     → only plugins/models/ollama-adapter/**        (0 external hits)
finance.trove.model.litellm    → only plugins/models/litellm-adapter/**       (0 external hits)
finance.trove.model.paddleocr  → plugins/models/paddleocr-adapter/** + services/receipt-processor/pom.xml (expected, accounted for in §6.5)
```

All five are safe to delete outright (module directory + pom `<module>` entry + parent-pom
`<dependencyManagement>` entries if any) with zero source-level fallout beyond the one pom.xml edit
already required for `receipt-processor`.

---

## 8. Target design

### 8.1 Destination packages for the 2 KEEP plugins

Fold both into `trove-core` as ingestion sub-packages, dropping the standalone-jar/auto-config
machinery entirely (no more `@AutoConfiguration`/`.imports` file — these become plain
`@Component`/`@Service` classes picked up by `trove-core`'s single component scan):

- `finance.trove.plugin.simplefin.*` → **`finance.trove.ingestion.simplefin`**
  - `SimplefinClient`, `SimplefinNormalizer`, `SimplefinPlugin` → keep as classes, drop
    `SimplefinAutoConfiguration` (replace its 3 `@Bean` methods with `@Component`/`@Service` on the
    classes directly, or a plain `@Configuration` class with no `@AutoConfiguration`/conditional
    machinery since there's now exactly one JVM and no "is this plugin on the classpath" question).
  - `SimplefinProperties` → merge into whatever single `@ConfigurationProperties("trove.simplefin")`
    class `trove-core` uses for this package; keep the same key names to avoid a silent config
    rename.
  - **Fix M0 in the same commit**: `RawTransaction(tx.id(), "simplefin", ...)` →
    `RawTransaction("simplefin", tx.id(), ...)` (or rename the field per §3.3/§O7 first, then wire
    correctly — do not fix the values without fixing the name, or the same swap can recur).
  - **Fix the SSRF gap** (§3.1 point 1) as part of the same package move, since the setup-token
    exchanger and the plugin now live in the same module for the first time — add a host/IP-range
    allowlist (reject RFC1918/loopback/link-local/`169.254.169.254`) to both
    `HttpSetupTokenExchanger` and any place `SimplefinClient` is given a user-supplied `accessUrl`.
  - **Wire the declared timeouts** — `SimplefinProperties.timeoutSeconds`/`connectTimeoutSeconds`
    are currently decorative (§3, config-keys table); attach them to the `RestClient`'s
    `ClientHttpRequestFactory` for real.

- `finance.trove.plugin.ofx.*` → **`finance.trove.ingestion.ofx`**
  - `OfxPlugin`, `OfxParser`, `OfxFormatDetector`, `OfxSgmlParser`, `OfxXmlParser`,
    `OfxNormalizer` → keep as-is structurally (this is the best-tested code in the plugin tree);
    drop `OfxAutoConfiguration`, register `OfxPlugin` as a plain bean.
  - **Fix M0**: `RawTransaction(tx.fitId(), SOURCE_ID, ...)` → correct field order, same caveat as
    above.
  - **Fix the hardcoded `DEFAULT_CURRENCY="USD"`** (§4.2) — thread the connected account's
    configured currency (CAD or USD, per onboarding) through instead, since this plugin currently
    cannot produce a correct CAD transaction at all.
  - **Make the upload sandbox path configurable** (§4, config-keys) instead of the hardcoded
    `/var/trove/uploads` constant, consistent with wherever `trove-core` lands its local-disk
    receipt/upload volume (MinIO is dropped per the owner's decision, so this is the same local-disk
    story, one config key, not two).
  - **Fix the SGML-encoding gap** (§4.1) — either honour the OFX1 header's declared `CHARSET`, or
    explicitly document/scope-limit to UTF-8/ASCII-safe OFX1 exports for v1 and file it as a known
    limitation rather than a silent corruption risk.
  - **Actually wire it up** — today no service depends on `ofx` at all (§0); the collapse is the
    first time this plugin gets a live caller. Needs a controller/endpoint for file upload (OFX was
    already headless/file-based, so this is new surface, not a revival of a broken one) plus a
    place in the sync/account UI for "import an OFX file" as an account type alongside SimpleFIN.

### 8.2 CSV import — new capability, not a plugin revival

None of the 7 plugins implement CSV. The owner's tax-time export requirement (categorised
transactions out as CSV/PDF) is the *outbound* direction; a CSV *import* path (many self-hosters'
banks offer CSV export even without OFX/SimpleFIN support) is a natural third `BankSourcePlugin`-
shaped addition to land alongside the simplefin/ofx move, reusing the same `RawTransaction`
contract and the now-fixed dedup semantics. Proposed design:

- **Package:** `finance.trove.ingestion.csv` (new — no existing code to migrate).
- **Column mapping:** accept a small set of common header spellings per logical field, matched
  case-insensitively, first match wins, with an explicit user-facing "map your columns" step for
  anything that doesn't auto-match (most bank CSV exports use idiosyncratic headers — sniffing
  alone will not cover every bank):
  | Logical field | Header candidates (case-insensitive) |
  |---|---|
  | date | `date`, `transaction date`, `posted date`, `trans date` |
  | description | `description`, `memo`, `payee`, `merchant`, `details` |
  | amount | `amount` (signed, single column — the common case) |
  | debit/credit (alt.) | `debit`/`withdrawal` + `credit`/`deposit` (two-column layout — sum with sign, or reject rows with both populated) |
  | currency | `currency` (optional; falls back to the connected account's configured currency, same fix as §8.1's OFX currency item — do **not** hardcode `"CAD"` or `"USD"` here, that repeats `01-FINDINGS.md` §F4's fallback-hardcoding defect pattern) |
  | balance (optional) | `balance`, `running balance` — informational only, not persisted onto `RawTransaction` |
- **Dedup key:** CSV exports carry no provider transaction id at all (unlike SimpleFIN's `tx.id()`
  or OFX's `FITID`), so there is nothing to put in the fixed-and-renamed
  `providerTransactionId`/`sourceId` slot. Two defensible options, and the fix should pick one
  explicitly rather than falling into a fifth ad hoc M0 variant:
  1. Leave the unique-per-transaction field **null** for CSV-sourced rows (the schema already
     supports this — `V2__transaction.sql:23`'s unique index is `WHERE source_id IS NOT NULL`) and
     rely entirely on `DeduplicationService`'s existing **fuzzy match** pass (±2 days, ±$0.50,
     same account) — already implemented, already tested, zero new dedup code needed. Simplest,
     and consistent with how a human re-importing "this month's CSV" a second time already behaves
     for pending→posted promotion elsewhere.
  2. Or synthesize a deterministic key — e.g. `sha256(accountId + date + amount + description)` —
     giving idempotent re-imports of the *identical* file without touching the shared dedup path.
     More precise, more code, and only pays for itself if repeat-uploading the exact same CSV file
     is a real self-hoster workflow worth optimising for.

  Recommendation: start with (1) — it costs nothing new and the fuzzy-match tolerance already
  exists for exactly this "no stable id" case (its own javadoc calls out the false-positive risk
  for small round-number repeats, which is an acceptable known tradeoff for a v1 CSV importer).
- **Reuse `OfxPlugin`'s sandbox pattern** (§4.1's path-traversal check) for the uploaded CSV file,
  rather than re-deriving it.
- **Size guard:** reuse OFX's 50 MB pattern as a starting default.

### 8.3 AI model access — collapse, don't migrate

Per the owner's decision (user-configured OpenAI-compatible endpoint, off until configured), the
`ModelAdapter` SPI, `model-adapter-api` library, and the whole ollama/litellm/paddleocr adapter
trio are **replaced**, not migrated, by a single class — proposed
`finance.trove.ai.OpenAiCompatibleClient` in `trove-core` — that:
- Takes a user-configured `base-url` + optional `api-key` (nullable/blank = feature off,
  no default host pointing at a same-network container the way ollama/litellm's adapters do today).
- Speaks the OpenAI Chat Completions (`/v1/chat/completions`) and Embeddings (`/v1/embeddings`)
  shapes — `plugin-model-litellm`'s `ChatCompletionRequest`/`ChatCompletionResponse`/
  `LiteLlmEmbedRequest`/`LiteLlmEmbedResponse` DTOs (§6.4) are the right starting point to copy in,
  since they already match this wire format field-for-field.
- Handles OCR by sending the receipt image as a vision-model chat message (base64 image + a
  structured-extraction prompt) against the same endpoint, rather than a dedicated OCR wire
  protocol — this is what makes PaddleOCR genuinely obsolete rather than merely descoped: the same
  one endpoint config now serves chat, categorisation, and receipt OCR.
- Drop `ModelCapability`/adapter-registry/routing entirely — there is one configured endpoint, not
  a fleet of adapters to route between, so the "does this adapter support this capability" question
  the SPI exists to answer no longer has a reason to exist.

---

## 9. Risks

1. **M0 must be fixed as a rename, not a value swap**, in both surviving normalizers
   (`finance.trove.ingestion.simplefin.SimplefinNormalizer`,
   `finance.trove.ingestion.ofx.OfxNormalizer`) — a value-only fix leaves the ambiguous field name
   in place for the *next* normalizer (CSV, §8.2, or any future source) to get wrong the same way a
   fourth time. Do the `sourceId` → `providerTransactionId` rename (§O7) as part of this move, not
   after.
2. **No test currently exercises the dedup boundary correctly.** `01-FINDINGS.md` §M10.4: every
   existing SimpleFIN `fetchTransactions` test passes `accountId=null`, the one input that never
   crosses `DeduplicationService`. Any fix to M0 needs a **new** integration-style test that
   actually calls `DeduplicationService.deduplicate` twice for the same account with two distinct
   `RawTransaction`s and asserts both persist — none of the ported unit tests currently do this,
   and porting them as-is would carry the blind spot forward silently green.
3. **OFX has zero production callers today** (§0, §4) — folding it in is *new* integration work
   (endpoint, account-type UI, currency threading), not a lift-and-shift of previously-working
   code. Size the collapse plan accordingly; don't budget it as "just move the package."
4. **SimpleFIN's per-sync full-history refetch** (§3.1) will get worse, not better, once M0 is
   fixed and transactions actually accumulate per account (today it's invisible because there's
   only ever one row per account to compare against) — every sync re-fetches and re-filters the
   entire bridge history client-side. Worth a follow-up ticket for a `since` high-watermark even
   though SimpleFIN's server-side date params aren't used today.
5. **Currency hardcoding is a repeated pattern, not one bug**: OFX's `DEFAULT_CURRENCY="USD"`
   (§4.2) is the same class of defect as `01-FINDINGS.md` §F1/§F4 (CAD hardcoded elsewhere,
   dedup's CAD/DEBIT fallback). The CSV importer's design (§8.2) deliberately avoids introducing a
   third instance — worth a lint/review checklist item during the merge: grep for literal
   `"CAD"`/`"USD"` string constants across whatever package `trove-core` lands ingestion in before
   calling that slice done.
6. **The SimpleFIN SSRF gap (§3.1) and the timeout-not-wired gap (§3, config table) are easy to
   lose** in a mechanical "move the package" pass, since neither one fails a test today — both are
   the kind of defect that only shows up under adversarial input or under load, not under the
   existing (hermetic, per `01-FINDINGS.md` §M10) test suite. Track them as explicit line items in
   whatever cutover checklist gates the simplefin move, not just "port the code."
7. **LiteLLM's DTOs are a starting point, not a finished client** (§6.4, §8.3) — copying
   `ChatCompletionRequest`/`Response` verbatim gets the happy-path shape right but carries none of
   the owner's "off until configured" requirement, none of a real timeout (repo-wide: "no HTTP
   timeout on ANY AI call" per `01-FINDINGS.md` §M11), and none of streaming (the DTO's `stream` is
   hardcoded `false` — `ChatCompletionRequest.java:11-14`'s own javadoc says so — while the coach
   feature the owner is keeping needs streaming completions). Budget the new
   `OpenAiCompatibleClient` as new work sized against the coach/categorisation requirements, with
   the old DTOs as a reference, not as a nearly-done component.
8. **Vision-model OCR is unproven in this codebase** — every existing OCR code path (`PaddleOcrAdapter`,
   §6.5) talks to an invented wire protocol nothing real implements, so there is no working
   reference implementation anywhere in the repo for "send an image to an OpenAI-compatible
   endpoint and get structured fields back." This is genuinely new integration work, not a
   protocol-fix of existing code, and should be scoped/estimated as such rather than folded into
   "receipts — one import line" framing from `01-FINDINGS.md` §L (that framing was specifically
   about the Minio import bug, not about OCR correctness).
