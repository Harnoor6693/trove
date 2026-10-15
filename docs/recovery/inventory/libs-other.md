# Inventory — libs/privacy-engine, libs/ai-gateway-client, libs/model-adapter-api

Scope: the three non-`common` shared libraries in the reactor, for the monolith collapse into
`trove-core`. Read-only pass; every claim below was checked against source in this working
tree, not inferred from javadoc or README text. Baseline: `docs/recovery/01-FINDINGS.md`
section O (O1 specifically) supersedes section M5 on privacy-engine — this report treats O1 as
ground truth and cites it rather than re-deriving it, while independently re-verifying every
grep O1 depends on.

LOC: privacy-engine 404 main / 250 test · ai-gateway-client 545 main / 337 test ·
model-adapter-api 298 main / 353 test. Total 1,247 main / 940 test across the three.

---

## 1. libs/privacy-engine

**Purpose.** Field-level data-sensitivity classification (`PrivacyTier` HIGH/MEDIUM/LOW) plus
an AOP mechanism (`@PrivacyEnforced` + `PrivacyEnforcementAspect`) intended to block
HIGH-tier data from reaching non-local AI models before the call is made.

**Public types (9), all in `finance.trove.privacy`:**
- `ModelType` (enum: LOCAL, CLOUD, HYBRID) — `ModelType.java:12-19`
- `PrivacyTier` (enum: HIGH, MEDIUM, LOW, ordinal = restrictiveness) — `PrivacyTier.java:18-29`
- `PrivacyContext` — static ThreadLocal carrier, defaults to HIGH, `withTier(...)` AutoCloseable — `PrivacyContext.java:20-55`
- `PrivacyClassifier` (interface: `classify`, `classifyPayload`) — `PrivacyClassifier.java:20-38`
- `RuleBasedPrivacyClassifier` — field-name → tier lookup, fail-safe default HIGH — `RuleBasedPrivacyClassifier.java:23-58`
- `PrivacyRules` (`@ConfigurationProperties("trove.privacy")`, sealed via `@PostConstruct`) — `PrivacyRules.java:33-59`
- `PrivacyEnforced` (`@Target(METHOD)` annotation, one attribute `modelType()`) — `PrivacyEnforced.java:25-28`
- `PrivacyEnforcementAspect` (`@Aspect`, one `@Around` advice) — `PrivacyEnforcementAspect.java:26-48`
- `PrivacyAutoConfiguration` — registers 2 beans — `PrivacyAutoConfiguration.java:36-49`
- `PrivacyViolationException` — thrown by the aspect — `PrivacyViolationException.java:14-19`

**Who depends on it** (grepped every `pom.xml` in the repo for `<artifactId>privacy-engine</artifactId>`):
only `libs/ai-gateway-client/pom.xml:19-22` declares it as a direct Maven dependency, plus the
root `pom.xml:210-214` `dependencyManagement` entry. **No service pom declares it directly.**
It reaches the classpath of exactly three services — `categorisation-service`, `model-manager`,
`receipt-processor` — solely because each of those declares `ai-gateway-client`, which in turn
pulls this in transitively. This matches O1 exactly (re-verified independently, not just cited).

**Auto-configuration registered.** Quoting the exact contents of
`libs/privacy-engine/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
finance.trove.privacy.PrivacyAutoConfiguration
```
That class registers two beans unconditionally (no `@ConditionalOn*` guard beyond classpath
presence): `privacyClassifier` (`RuleBasedPrivacyClassifier`) and `privacyEnforcementAspect`
(`PrivacyEnforcementAspect`) — `PrivacyAutoConfiguration.java:40-48`.

**The privacy aspect (M5 / O1 — re-verified here).**
Pointcut, verbatim, `PrivacyEnforcementAspect.java:30`:
```java
@Around("@annotation(finance.trove.privacy.PrivacyEnforced)")
```
A repo-wide grep for `PrivacyEnforced` outside `libs/privacy-engine` returns **zero** hits in
any `src/main` tree; the only three usages of the annotation anywhere in the repo are in the
library's own test fixture (`PrivacyEnforcementAspectTest.java:68,71,74`). So the bean is real
— Spring instantiates it, and if AspectJ weaving is active it is a correctly-registered advice
— but it advises nothing on any production call path. It is not "dead code" in the classloading
sense; it is a live, wired, no-op.

**Two enforcement paths, opposite policies — confirmed, and the disagreement is specifically
about MEDIUM tier.**
- `PrivacyEnforcementAspect.java:37`: `allowed = !currentTier.isMoreRestrictiveThanOrEqualTo(HIGH) || modelType == LOCAL`
  — only blocks when tier == HIGH *and* modelType != LOCAL. MEDIUM-tier data reaching a CLOUD
  model type is explicitly **allowed** — asserted by
  `PrivacyEnforcementAspectTest.java:43-46` (`mediumTierWithCloudModelIsAllowed`).
- `ModelRoutingService.java:35-41` (in ai-gateway-client): the `MEDIUM` branch throws
  `PrivacyViolationException` for `CLOUD_QUALITY`, identically to the `HIGH` branch
  (`ModelRoutingService.java:32-33`). MEDIUM-tier data reaching cloud is **refused**.

  Because the aspect never fires (previous point), only `ModelRoutingService`'s stricter policy
  actually executes today — O1's framing that "only one path executes in practice" is correct.
  The risk is latent, not active: the contradiction reappears the instant anyone adds
  `@PrivacyEnforced` to a real method expecting the documented (weaker) MEDIUM behaviour. See
  Risk 1.

**`PrivacyTier` and `PrivacyViolationException` are live** — re-verified by grep for
`finance.trove.privacy` imports outside the library: `ai-gateway-client` (`AiRequest.java`,
`AiAuditLog.java`, `AiGatewayClient.java`, `AiGatewayAutoConfiguration.java`, `ModelAlias.java`,
`ModelRoutingService.java`), `model-adapter-api` (`ModelInfo.java` — javadoc cross-reference
only, not an import), `plugins/bank-sources/imap`, `services/categorisation-service`,
`services/receipt-processor`, `services/model-manager`. `PrivacyContext` and
`PrivacyClassifier`/`RuleBasedPrivacyClassifier`, by contrast, have **zero** references
anywhere outside `libs/privacy-engine`'s own `src` tree (both main and test) — confirmed by
grep. Nothing outside this library ever sets a tier via `PrivacyContext`, and nothing outside
it ever calls `classify()`/`classifyPayload()`.

**Duplicate `PrivacyViolationException`.** There are two classes with the identical simple
name and different shapes:
- `finance.trove.privacy.PrivacyViolationException` — `PrivacyViolationException(PrivacyTier tier, String modelAlias)` — `libs/privacy-engine/.../PrivacyViolationException.java:16-18`
- `finance.trove.ai.gateway.PrivacyViolationException` — `PrivacyViolationException(String message)` — `libs/ai-gateway-client/.../PrivacyViolationException.java:19-21`

The gateway copy's own javadoc calls this out as deliberate: *"this is the gateway-client
package's local copy... the two are distinct and should not be conflated"*
(`libs/ai-gateway-client/.../PrivacyViolationException.java:14-16`). Deliberate or not, folding
both packages into one `trove-core` module puts both classes in the same reachable import
scope — see Risk 2.

**`privacy-rules.yml` is never loaded — re-verified.** Grep for `privacy-rules` (filename) and
for `spring.config.import` / `trove.privacy` across every `application.yml` in
`categorisation-service`, `model-manager`, and `receipt-processor` (the only three services that
even get this library on their classpath) returns nothing. `PrivacyRules` fields default to
`new ArrayList<>()` (`PrivacyRules.java:36-38`), so `RuleBasedPrivacyClassifier` is constructed
with three empty sets in every real deployment, and `classify()` falls through to its HIGH
default for every field (`RuleBasedPrivacyClassifier.java:50`) — moot anyway, since nothing
calls `classify()` from outside the library (previous point). Dead data feeding a classifier
that nothing invokes.

**Recommendation (KEEP / DELETE, destination package `finance.trove.core.privacy`):**

| Type | Verdict | Notes |
|---|---|---|
| `PrivacyTier` | **KEEP** | Genuinely load-bearing; used by 4 real call sites today. |
| `PrivacyViolationException` | **KEEP (one)** | Consolidate with the `ai.gateway` duplicate into a single class; pick one constructor shape (recommend the `(PrivacyTier, String)` form — it's more informative). |
| `ModelType` | DELETE | Only consumer is the dead annotation. |
| `PrivacyEnforced` | DELETE | Zero real usages; repair risk (Risk 1) outweighs any value of keeping it "just in case." |
| `PrivacyEnforcementAspect` | DELETE | Advises nothing; also drops the `spring-boot-starter-aspectj` dependency from trove-core (Risk 7). |
| `PrivacyAutoConfiguration` | DELETE | Nothing left to register once the aspect and classifier go. |
| `PrivacyContext` | DELETE, or fold into the new single egress method (§5) as an internal detail — not worth keeping as a public ThreadLocal API with zero current callers. |
| `PrivacyClassifier` / `RuleBasedPrivacyClassifier` / `PrivacyRules` / `privacy-rules.yml` | DELETE as-is | The field-name-tiering *idea* is reasonable and worth reusing inside the new single egress point (§5), but the existing classes/config were never proven against real payloads (Risk 3) — treat as a design reference, not a lift-and-shift. |

---

## 2. libs/ai-gateway-client

**Purpose.** Privacy-aware model routing + `{{variable}}` prompt templating + HTTP transport
to a LiteLLM proxy's OpenAI-compatible `/v1/chat/completions` endpoint + fire-and-forget async
audit logging, so calling services don't coordinate those four concerns themselves.

**Public types, all in `finance.trove.ai.gateway`:**
`AiAuditLog` (record) · `AiGatewayAutoConfiguration` · `AiGatewayClient` · `AiGatewayProperties`
(`@ConfigurationProperties("trove.ai")`) · `AiRequest` (record, + static factory `of(...)`) ·
`AiResponse` (record, + static factories `success`/`failure`) · `LiteLlmRestClient` (+ nested
`ChatMessage`, `Choice`, `Usage`, `ChatResponse` records) · `ModelAlias` (enum: LOCAL_FAST,
LOCAL_QUALITY, HYBRID_EFFICIENT, CLOUD_QUALITY) · `ModelRoutingService` ·
`PrivacyViolationException` (the duplicate — see §1).

**Who depends on it** (grepped every `pom.xml` for `<artifactId>ai-gateway-client</artifactId>`):
`plugins/bank-sources/imap/pom.xml`, `services/categorisation-service/pom.xml`,
`services/receipt-processor/pom.xml`, `services/model-manager/pom.xml`, plus the root
`pom.xml:215-219` dependencyManagement entry — 4 dependents. But declared-dependency ≠
real-usage, verified per dependent:
- `categorisation-service` and `receipt-processor` call `AiGatewayClient.complete()` for real
  work (`AiCategorisationService.java`, `AiExtractor.java`).
- `plugins/bank-sources/imap` also calls it, but IMAP ingestion is cut by owner decision
  regardless, and per `01-FINDINGS.md` M3/M9 the plugin is on no service's classpath and
  forwards the user's entire unescaped inbox into a prompt — delete the whole plugin, this
  dependency goes with it.
- `services/model-manager` declares the dependency but **does not call `AiGatewayClient` or
  `LiteLlmRestClient` at all** — grep confirms its only imports from this package are
  `ModelAlias` and `ModelRoutingService` (`ChatService.java:3-4`), reused purely for
  model-manager's own internal routing decision. The actual HTTP call to an LLM happens in
  `ChatController`, whose own javadoc states it *"proxies completion requests directly to the
  Ollama API"* (`services/model-manager/.../ChatController.java:31`) — a **third**, separate,
  undocumented AI-calling code path, parallel to and never reconciled with `ai-gateway-client`.

**Auto-configuration registered.** Quoting
`libs/ai-gateway-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
finance.trove.ai.gateway.AiGatewayAutoConfiguration
```
Registers `modelRoutingService`, `liteLlmRestClient`, `aiGatewayClient`
(`AiGatewayAutoConfiguration.java:33-47`).

**API surface — what an OpenAI-compatible client replacement must offer (checked against what
this module actually does today, not what its javadoc claims):**

| Capability | Present today? | Evidence |
|---|---|---|
| Chat completion (blocking) | **Yes** | `LiteLlmRestClient.complete()` POSTs `{model, messages}` to `/v1/chat/completions` and parses `choices[0].message.content` + `usage.{prompt_tokens,completion_tokens}` — standard OpenAI shape (`LiteLlmRestClient.java:62-92`). Directly portable to any OpenAI-compatible endpoint. |
| Streaming (SSE) | **No** | The request body never sets `stream: true`; the response is read as one `String` (`.body(String.class)`, `LiteLlmRestClient.java:75`) and parsed as a single JSON document (`:78`). No chunked/SSE parsing exists anywhere in this module. Note: `model-adapter-api`'s `CompletionRequest` *does* carry a `stream` boolean field (`CompletionRequest.java:20`), but nothing in `ai-gateway-client` reads or forwards it from `AiRequest` — it's a vestigial parameter with no wiring on this side. |
| Vision / image input | **No** | `ChatMessage` is `record ChatMessage(String role, String content)` — `content` is a plain `String` (`LiteLlmRestClient.java:33`), not a content-parts array, so there's no way to attach image bytes or a data URI to a message. Confirmed no image/base64/byte[] handling anywhere in `receipt-processor`'s `AiExtractor.java` either (101 lines, no such tokens) — today's receipt pipeline does not attempt vision at all through this client. |
| Embeddings | **No** | `LiteLlmRestClient` exposes only `complete()`. No `/v1/embeddings` call exists anywhere in this module, despite the sibling `model-adapter-api` already defining `EmbeddingRequest`/`EmbeddingResponse` types for exactly this purpose — those types are unused by this client. |

Net: only plain blocking chat completion exists. Streaming, vision, and embeddings are new
build work, not rewiring — see Risk 4.

**Async audit logging is cross-service and independently confirmed broken (M11), re-verified
here at the exact property level.** `AiGatewayClient` POSTs to
`{modelManagerUrl}/internal/audit` with header `X-Service-Token` sourced from
`properties.getServiceToken()` (`AiGatewayClient.java:88-93`), bound from
`trove.ai.service-token` → env `TROVE_AI_SERVICE_TOKEN` (default `dev-ai-service-token`,
`docker/docker-compose.yml:199`). The receiver, `model-manager`'s `AuditController`, reads
`@Value("${trove.internal.service-token}")` (`AuditController.java:47`), bound from
`TROVE_INTERNAL_SERVICE_TOKEN` (default `dev-internal-service-token`,
`docker/docker-compose.yml:457`) — a **different env var with a different default value**,
confirmed mismatched identically in `docker-compose.yml`, `docker-compose.release.yml`, and
`docker-compose.integration.yml`. Every audit POST 401s; the failure is caught and only logged
(`AiGatewayClient.java:94-96`), so it fails silently. In the monolith this whole HTTP hop
disappears by construction (§5) — see Risk 6 for why that must be a deliberate deletion, not
an incidental side effect someone "fixes" instead.

`AiGatewayProperties.validate()` (`@PostConstruct`, `AiGatewayProperties.java:64-69`) throws
`IllegalStateException` at startup if `serviceToken` is blank — relevant only if the HTTP audit
call survives; recommend deleting both together.

**Recommendation:**

| Type | Verdict | Notes |
|---|---|---|
| `AiRequest`, `AiResponse`, `ModelAlias`, prompt-substitution logic in `AiGatewayClient` | **KEEP** | Move into `trove-core`'s AI package; dependency-free, well-validated, reusable as-is. |
| `ModelRoutingService` | **KEEP the idea, rewrite the implementation** | Fold it into the single egress point (§5) so it's the *one* policy, not one of two. |
| `LiteLlmRestClient` | **Partially keep, mostly rewrite** | Retry/timeout skeleton and the chat-completions request/response parsing are reusable; target changes from "a LiteLLM proxy sidecar" to "the user's configured OpenAI-compatible endpoint directly" (owner decision), and streaming/vision/embed need to be added. |
| `AiGatewayAutoConfiguration`, the cross-service audit POST, `AiGatewayProperties.serviceToken` + `validate()` | **DELETE** | Replace with a local Postgres audit-table write inside the new single egress method. |
| `PrivacyViolationException` (this package's copy) | **DELETE** | Consolidate on the one kept in §1. |

---

## 3. libs/model-adapter-api

**Purpose.** SPI (interface + DTOs) meant to let `model-manager` address multiple AI backends
(Ollama, LiteLLM/cloud, PaddleOCR) uniformly through one `ModelAdapter` contract.

**Public types (9), all in `finance.trove.model.adapter`:**
`CompletionRequest` (record, + nested `Message`) · `CompletionResponse` (record) ·
`EmbeddingRequest` (record) · `EmbeddingResponse` (record) · `ModelAdapter` (interface:
`getId`, `getDisplayName`, `getCapabilities`, `listAvailableModels`, `complete`, `embed`, `ocr`,
`isHealthy` — `ModelAdapter.java:22-58`) · `ModelAdapterException` · `ModelCapability` (enum:
TEXT_COMPLETION, EMBEDDINGS, OCR) · `ModelInfo` (record) · `OcrResult` (record, + nested
`BoundingBox`). This is the most carefully written of the three modules: every record has a
compact constructor enforcing non-null / non-blank / range checks and defensive
`List.copyOf`/`Set.copyOf` copies (e.g. `CompletionRequest.java:23-28`,
`OcrResult.java:20-26`, `ModelInfo.java:23-29`) — genuinely solid DTOs, independent of the fate
of the interface around them.

**No auto-configuration file** — `find` confirms there is no `META-INF/spring` directory in
this module at all; it's a pure API/DTO jar with zero Spring dependency in `main`
(`pom.xml` only pulls `lombok` and `spring-boot-starter-test`). Auto-configuration lives one
layer out, in each *implementation* module — e.g. `OllamaAdapter`'s own javadoc: *"Beans are
registered by `OllamaAutoConfiguration`"* (`OllamaAdapter.java:47`), a class that lives in
`plugins/models/ollama-adapter`, not here.

**Who depends on it** (grepped every `pom.xml` for `<artifactId>model-adapter-api</artifactId>`):
`plugins/models/litellm-adapter/pom.xml`, `plugins/models/paddleocr-adapter/pom.xml`,
`plugins/models/ollama-adapter/pom.xml` (the 3 implementations), `services/model-manager/pom.xml`,
`services/receipt-processor/pom.xml`, plus the root `pom.xml:220-224` dependencyManagement entry.

**Critical gap, corroborating `01-FINDINGS.md` M6 independently:** `services/model-manager/pom.xml`
depends on the SPI *interface* but declares **none** of the three implementation artifacts
(`litellm-adapter`, `paddleocr-adapter`, `ollama-adapter`) — grepping those three artifactIds
inside `services/model-manager/pom.xml` returns nothing. So `ModelHealthScheduler`'s
`@Autowired(required=false)` adapter fields (per M6) can never resolve to a real bean: the
interface is real and well-designed, but there is no wiring from "adapter implementation jar"
to "the one service meant to route through it," in the current tree.

**Three `ModelAdapter` implementations exist** (grep-confirmed: `implements ModelAdapter`):
`LiteLlmAdapter` (`plugins/models/litellm-adapter`), `PaddleOcrAdapter`
(`plugins/models/paddleocr-adapter`), `OllamaAdapter` (`plugins/models/ollama-adapter`). None
of the three classes carries a `@Component`/`@Service` annotation itself — each relies on its
own sibling `*AutoConfiguration` class to register the bean (confirmed for Ollama via its
javadoc, cited above). Against the owner's decisions: PaddleOCR is cut outright;
LiteLLM-as-a-sidecar is cut (replaced by a direct user-configured OpenAI-compatible endpoint);
only Ollama's *backend target* (a local daemon) survives as a real destination, though its
adapter code doesn't need to survive as a separate pluggable class in a monolith with one
configured endpoint at a time.

**`receipt-processor`'s dependency** is for the DTOs (`OcrResult`, `ModelAdapterException`)
consumed by its OCR pipeline, not for `ModelAdapter` implementations — it has none of its own.

**Recommendation (destination package `finance.trove.core.ai`):**

| Type | Verdict | Notes |
|---|---|---|
| `CompletionRequest`/`Response`, `EmbeddingRequest`/`Response`, `ModelInfo`, `ModelCapability`, `ModelAdapterException`, `OcrResult` (+`BoundingBox`) | **KEEP** | Clean, validated DTOs — the shared vocabulary for the single AI client in trove-core, independent of whether the `ModelAdapter` interface survives. |
| `ModelAdapter` interface | **DELETE** | It's a multi-backend routing abstraction (capability-based dispatch across N adapters, per-adapter health checks, adapter registry) for a plugin-registry problem the monolith no longer has — owner's decision folds the plugin architecture into core, and there is exactly one user-configured endpoint at a time, not N to route across. A single concrete client class replaces the interface + 3 implementations + the (already-broken, M6) registry/health-check machinery in model-manager. |
| `litellm-adapter`, `paddleocr-adapter`, `ollama-adapter` modules (`plugins/models/`) | **DELETE** | PaddleOCR and LiteLLM-sidecar backends are cut by owner decision. `OllamaAdapter`'s HTTP-calling code is a reasonable *reference* for the new single client's local-model path, but the module itself does not need to survive as a separate adapter jar. |

---

## 4. Dependents map

Consolidated from grepping every `pom.xml` in the repo for each artifactId (raw dependency
declarations; "real usage" column adds what source-level grep shows once the dependency is
declared):

| Dependent pom.xml | privacy-engine | ai-gateway-client | model-adapter-api | Real usage notes |
|---|---|---|---|---|
| `libs/ai-gateway-client/pom.xml` | direct | — | — | Uses `PrivacyTier`/`PrivacyViolationException` types only; never sets `PrivacyContext`, never calls `PrivacyClassifier`. |
| `plugins/bank-sources/imap/pom.xml` | transitive | direct | — | Calls `AiGatewayClient.complete()` for real, but the plugin is cut (IMAP ingestion) and already on no service's classpath (M3/M9) — deleted regardless. |
| `services/categorisation-service/pom.xml` | transitive | direct | — | Real caller of `AiGatewayClient.complete()` (`AiCategorisationService.java`) — this is genuine production usage to preserve. |
| `services/receipt-processor/pom.xml` | transitive | direct | direct | Real caller of `AiGatewayClient.complete()` (`AiExtractor.java`); consumes `OcrResult`/`ModelAdapterException` DTOs from model-adapter-api, but implements no `ModelAdapter` itself. |
| `services/model-manager/pom.xml` | transitive | direct | direct | Declares both, uses neither's main entry point: only reuses `ModelAlias`/`ModelRoutingService` *types* from ai-gateway-client (`ChatService.java`) for its own routing decision, then talks to Ollama directly from `ChatController` — a third, undocumented AI call path. Declares `model-adapter-api` but wires none of its 3 implementations (M6). |
| `plugins/models/litellm-adapter/pom.xml` | — | — | direct | Implements `ModelAdapter`; not consumed by model-manager. Cut (LiteLLM sidecar dropped). |
| `plugins/models/paddleocr-adapter/pom.xml` | — | — | direct | Implements `ModelAdapter`; not consumed by model-manager. Cut (PaddleOCR dropped by owner decision). |
| `plugins/models/ollama-adapter/pom.xml` | — | — | direct | Implements `ModelAdapter`; not consumed by model-manager. Cut as a separate module; HTTP logic reusable as reference. |
| root `pom.xml` | dependencyManagement (`:210-214`) | dependencyManagement (`:215-219`) | dependencyManagement (`:220-224`) | Version pinning only; also lists all three as reactor `<module>`s (`:16-18`). |

Takeaway: of 6 pom-level dependents, only 2 (`categorisation-service`, `receipt-processor`) call
`AiGatewayClient` for real production work. `model-manager` declares both AI libraries but
routes around both of them via its own direct-to-Ollama path. `imap` is cut outright. This
narrows what actually needs porting into `trove-core` considerably.

---

## 5. Target design — the minimal honest privacy model for the monolith

One egress point, deny-by-default, logged — concretely:

1. **One class, one method.** A single `finance.trove.core.ai.AiGatewayService` (name
   illustrative) is the *only* code in `trove-core` allowed to open an HTTP connection to the
   user-configured OpenAI-compatible endpoint (Ollama or cloud). No service package gets its
   own `RestClient` to an AI backend — the direct-to-Ollama path in today's `ChatController`
   (§2, third undocumented path) is exactly the mistake this rule exists to prevent. In a
   single JVM there's no module boundary to lean on to enforce this mechanically the way
   separate services once nominally did (and didn't — see O1); it has to be a stated code-review
   rule, not just a design intent (Risk 5 below explains why the alternative recreates today's
   bug).
2. **Deny-by-default.** Per owner decision, AI is off until the user configures an endpoint.
   The gateway method checks configuration first and returns a clear "AI not configured" result
   — never silently falls back to some other default endpoint.
3. **One privacy policy, folded in, not bolted on.** Replace the two-file AOP-vs-switch-statement
   split (§1) with the field-tiering idea evaluated inline, in the same method that makes the
   HTTP call: classify the outbound payload (the `RuleBasedPrivacyClassifier` field-list
   approach is a reasonable starting heuristic, but re-validate its 45 field names against
   trove-core's actual transaction/receipt DTOs before trusting it — Risk 3), and **refuse**
   (don't downgrade) a HIGH-tier payload against a CLOUD-classified endpoint. One function, one
   policy — nothing left to drift out of sync with itself.
4. **Real capability surface**, built to what the kept features actually need (§2's gap table):
   blocking chat completion (already have a working reference), streaming SSE for a responsive
   coach chat (new work), vision/image content-parts for receipt OCR (new work — required,
   since PaddleOCR is cut and OCR now depends entirely on the vision-capable endpoint).
   Embeddings: **no kept feature calls `embed()` anywhere in the audited code** — recommend
   dropping it from the MVP surface rather than building unused capability; revisit only if a
   real feature (e.g. semantic search over transactions) needs it later.
5. **Logged as a local write, not a cross-service HTTP call.** Every call (success or failure)
   writes one row to a Postgres `ai_audit_log` table via the same connection pool as everything
   else in trove-core — this removes the `TROVE_AI_SERVICE_TOKEN` vs
   `TROVE_INTERNAL_SERVICE_TOKEN` mismatch (§2, M11) by construction, not by fixing the token
   names (Risk 6).

---

## 6. Risks

1. **Repairing `@PrivacyEnforced` in place reintroduces the MEDIUM-tier contradiction.**
   The annotation and aspect are proven dead (O1), but they read as a complete, tested,
   documented mechanism. If someone "finishes wiring it up" by adding `@PrivacyEnforced` to
   real methods without first reconciling its policy against `ModelRoutingService`'s stricter
   one (§1), the exact "two enforcement paths, opposite policies" defect M5 flagged goes from
   latent to active. The recommendation is deletion specifically to close this off, not repair.
2. **Duplicate `PrivacyViolationException` becomes an import-resolution hazard during the merge.**
   Two classes, same simple name, different constructors (`(PrivacyTier, String)` vs
   `(String)`), currently kept apart by package boundaries the merge is about to erase. Must be
   consolidated deliberately as part of the move, not left for the compiler to catch — a
   compatible-looking constructor added later could compile against the wrong one silently.
3. **`RuleBasedPrivacyClassifier`'s field list was never validated against real payloads.**
   No code anywhere in the audited repo calls `classify()`/`classifyPayload()` outside the
   library's own unit tests. If reused in the new single-egress design (§5), the 45-entry list
   needs re-checking against trove-core's actual transaction/receipt/categorisation field names
   before being trusted to gate anything.
4. **ai-gateway-client's real capability surface is chat-only.** No streaming, no vision, no
   embeddings exist today (§2 table) despite `model-adapter-api` already defining DTOs that
   imply some of these. Scope the vision-capable OCR path and streaming coach chat as new
   implementation work, not "reconnect the existing client."
5. **Porting the `ModelAdapter` SPI forward risks recreating today's broken indirection.**
   The interface is well-designed for routing across N backend plugins, a problem the monolith's
   "one user-configured endpoint" decision removes. Keeping it invites the same failure mode
   that already exists (`ModelHealthScheduler` autowiring adapters that aren't on the classpath,
   M6) — a registry with nothing correctly registered. Collapse to one concrete client instead.
6. **The audit-log token mismatch (M11) must be deleted, not fixed.** If someone instead aligns
   `TROVE_AI_SERVICE_TOKEN`/`TROVE_INTERNAL_SERVICE_TOKEN` and keeps the HTTP POST, the
   monolith ends up with one JVM issuing an HTTP call to itself for what should be a local
   `repository.save()` (§5 point 5) — a correctness fix that leaves an unnecessary network hop
   in place.
7. **Dropping the aspect drops a real dependency, not just dead code.** `privacy-engine/pom.xml`
   pulls `spring-boot-starter-aspectj` for a pointcut that matches nothing (§1). Deleting the
   aspect removes AspectJ weaving from trove-core's classpath entirely — worth stating
   explicitly in the collapse plan since it's easy to overlook "just moving files" and instead
   carry the dependency forward unnecessarily.
