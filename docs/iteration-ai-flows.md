# Iteration: which model reads what — per-flow provider + model

> **Retrospective doc.** Written during the 2026-07-27 catch-up from `5dc2a9a`, `0801c35`,
> `7aaeca0` and the working-tree refactor that finished it (`AiFlow`, `AiExtractors`,
> `AiFlowsProperties`). Verified at symbol level; **the Gradle run is the user's.**

- **Status:** ✅ code complete — needs `./gradlew build` to confirm
- **Migration:** none
- **PWA:** none

## Why one model was the wrong shape

The recognition jobs are genuinely different tasks, not one task in different clothes:

- a **receipt** is a small printed table, read many times a day → cheap and fast is what matters;
- an **A3 measure plan** is dense line-work, read in several passes per project → the strongest
  vision available pays for itself.

Forcing both onto one model means either overpaying on every receipt or under-reading every
drawing. There is no single right answer, so the config stopped pretending there was one.

## The shape

- **`AiFlow`** — the jobs: `ESTIMATE`, `RECEIPT`, `SKETCH`, `ELECTRICAL`, `PROJECT_DOCS`.
  `flow.key()` is the config key (`PROJECT_DOCS` → `project-docs`).
- **`JsonExtractor`** — the seam: `requestJson(input, systemPrompt, schema)` + `providerName()`.
  Implemented by `AnthropicJsonExtractor`, `OpenAiJsonExtractor`, `MisconfiguredJsonExtractor`.
- **`AiExtractors`** — resolves flow → extractor **once at startup** and logs the whole mapping.
  Services call `extractors.forFlow(AiFlow.X)`: a lookup, not a decision.
- **`AiFlowsProperties`** (`app.ai.*`) — `provider`, optional `model`, and
  `flows.<key>` = `vendor:model` | just a model | just a vendor.

A third vendor is one more `JsonExtractor` plus one branch in `build` — **no service changes.**
`EstimateExtractor` (formerly `ClaudeEstimateExtractor`) is the visible half of that split: it
now owns only the prompt and the schema, not the transport.

## Four decisions worth not undoing

1. **Nothing set changes nothing.** Every flow falls back to the default extractor — exactly the
   behaviour before this existed. A refactor that changes behaviour by default is a refactor
   nobody can safely merge.
2. **Resolution happens at startup and the mapping is logged.** "Which model produced this
   reading" is the first question about a bad result, and it must be answerable from the log
   rather than by re-deriving config. It also keeps quality comparisons honest: one model per
   flow per run, not whatever a request happened to pick.
3. **A typo disables that ONE flow and says so.** An unknown vendor, or a vendor whose API key is
   unset, yields a `MisconfiguredJsonExtractor` → the usual 503 for that flow only. It must not
   quietly fall back to the default: *results attributed to a model nobody chose are worse than
   results that never came.*
4. **A present-but-blank config value is treated as absent.** `${AI_FLOW_RECEIPT:}` with nobody
   setting the variable arrives as an empty string — the exact trap that once turned an unset
   provider into 25 failed integration tests.

One instance per distinct spec, too: two flows on the same vendor+model share a client rather
than opening a second connection to the same destination.

## Testing

`AiExtractorsTest` (resolution, fallback, unknown vendor, missing key, sharing), `AiHttpTest`,
`AiProviderConfigTest`, `OpenAiJsonExtractorTest`, `AnthropicInputRenderingTest`, and the four
service tests migrated onto the new seam (`EstimateExtractorTest` — renamed from the Claude-named
one — plus sketch / project-import / receipt / electrical).

## Gotchas
- **`ClaudeAlbumExtractor` is deliberately outside this registry.** A whole-album pass runs for
  minutes and it keeps its own HTTP client with longer timeouts; it joins `AiFlow` when the seam
  learns to carry a timeout. Don't "unify" it before then.
- OpenAI's `maxTokens` is the **whole thinking budget** — reasoning tokens bill as output and
  count against it. At 8 000 a dense drawing can burn the budget mid-thought and return an
  incomplete answer you already paid for; their own advice is ≥ 25 000.
- The old class name `ClaudeEstimateExtractor` survives only in comments explaining the split.
