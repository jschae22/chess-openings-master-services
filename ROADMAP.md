# Chess Opening Master — AI/RAG Roadmap

Portfolio goal: evolve the existing AI coach from a bare LLM call (no grounding) into a
production-quality RAG feature — grounded answers, an eval harness, and a Claude-based
backend — as a demonstrable "AI Engineer" portfolio piece built on an existing Spring Boot
service.

Repo layout:
- `frontend/` — React + TS (Vite), chat UI, calls `/api/chat`
- `backend/` — Spring Boot service, `ChatController` / `ClaudeService`

---

## Working agreement: how changes ship

All work follows [`cicd-pipeline.md`](cicd-pipeline.md) (trunk-based: short-lived branch → PR →
squash merge to `main` → auto-deploy to prod). Before starting or resuming any roadmap item:

1. **Check git first:** `git status -sb`, `git branch -a`, and `git fetch` to see what `main` has
   gained. Don't assume the current branch is the right one: a previous branch may already be
   squash-merged, and `main` may have moved (dependency bumps, migrations).
2. **Pick the branch:** continue the existing branch if it covers this roadmap item and is still
   open; otherwise cut a new one from an up-to-date `main` (stash uncommitted work, switch, pull,
   `git switch -c`, pop).
3. **Name and scope it** per `cicd-pipeline.md` §1: `<type>-<scope-words>`, one roadmap item (or
   one tightly coupled pair) per branch, merged within about a week.
4. **Before the PR:** CI must be green (`Test & Build`), run `/code-review`, and if the change
   adds a Flyway migration or needs an ingestion re-run, say so in the PR's deploy notes. Staging
   and prod share one Neon DB, so migrations must be additive.
5. **Cross-repo items** (e.g. frontend grounding notice) reuse the same branch name in the
   frontend repo and link the PRs.

---

## Phase 0 — Housekeeping (do first)

- [x] Commit the currently-uncommitted backend work (`chat/` package, `SecurityConfig`,
      `OpeningController`, `application.yml`, `mvnw`/`mvnw.cmd`) so the existing Gemini
      integration has real git history before it gets migrated away.
- [x] Get Anthropic API access at console.anthropic.com and generate an API key (individual
      account, not identity-federated — no org SSO needed for a personal project).
- [x] Store the key the same way `gemini.api-key` was stored: locally in
      `application-local.yml` (gitignored, confirmed never tracked), and in prod as the
      `claude-api-key` secret in GCP Secret Manager, with the Cloud Run service account
      granted `roles/secretmanager.secretAccessor` on it.

## Phase 1 — Gemini → Claude migration (no RAG yet)

Goal: swap the model provider with zero behavior change, so the migration and the RAG work
are two separately-reviewable, separately-demoable steps.

- [x] Replace `GeminiService` with `ClaudeService`, calling the Anthropic Messages API.
      Used the official `com.anthropic:anthropic-java` SDK (v2.57.0). System prompt (coach
      persona/instructions) and user message (opening/moves/FEN/question context) are now
      split via the Messages API's native `system` field, instead of one flattened prompt
      string.
- [x] Pick a model: made configurable via `claude.model` (env `CLAUDE_MODEL`), defaulting to
      `claude-haiku-4-5`. Swap to a Sonnet model id for quality comparisons without a code
      change.
- [x] Port `ChatRequest`/`ChatResponse`/`GeminiException` → generic names
      (`ChatException`) not tied to a specific provider.
- [x] Keep `RateLimiterService` as-is (provider-agnostic already — no change needed).
- [x] Update config references from Gemini → Claude: `application.yml`,
      `application-local.yml(.template)` (`gemini.api-key` → `claude.api-key` +
      `claude.model`), and the backend's `cd.yml` Cloud Run secret mapping
      (`GEMINI_API_KEY=gemini-api-key` → `CLAUDE_API_KEY=claude-api-key`). No backend
      `.env.example`/README existed to update — the frontend's Gemini references are a
      separate repo/pipeline and are still open (see note below).
- [x] Manually verify the chat feature still works end-to-end (frontend → nginx →
      `/api/chat` → Claude → response rendered). Verified locally on 2026-08-23: Postgres via
      `docker-compose`, backend on the `local` profile (port 8080), frontend via `npm run dev`
      (port 3000, Vite proxies `/api` → `localhost:8080`, standing in for the nginx reverse
      proxy). Opened the app in-browser, selected the Sicilian Defense, asked the AI Coach a
      question, and got a grounded, correctly-formatted Claude response rendered in the panel.

## Phase 2 — Ground the coach: RAG over opening theory

Goal: replace the "just the opening name + moves + FEN" prompt with real theory content, so
answers are grounded rather than free-associated.

- [x] **Embeddings provider.** Decided: Voyage AI (Anthropic's recommended embeddings
      partner) — `voyage-4-lite`/`voyage-4` pricing is negligible at this project's scale
      (well within the 200M free tokens), and it avoids standing up a separate Python/ONNX
      runtime just for embeddings the way local `sentence-transformers` would (backend is
      Java/Spring). Local embedding remains worth a one-line mention in the README as a
      considered tradeoff. API key obtained and placed locally in `application-local.yml`
      (gitignored); `voyage.api-key`/`voyage.model` (default `voyage-4-lite`) wired into
      `application.yml`, `application-local.yml.template`, and the backend's `cd.yml`
      Cloud Run secret mapping (`VOYAGE_API_KEY=VOYAGE_API_KEY`), mirroring the Claude key
      setup from Phase 0. `VOYAGE_API_KEY` secret created in GCP Secret Manager and the
      Cloud Run runtime service account (`…-compute@developer.gserviceaccount.com`) granted
      `roles/secretmanager.secretAccessor` on it; staging deploy verified 2026-08-28. All
      Cloud Run secrets standardised to `UPPER_SNAKE` names at the same time
      (`neon-db-url`→`NEON_DB_URL`, `claude-api-key`→`CLAUDE_API_KEY`).
- [x] **Corpus.** Drafted as structured YAML (not the flat `description` column) under
      `backend/src/main/resources/theory/<slug>.yaml`, one file per opening, covering all 9
      seeded openings. Each file has an opening-level overview plus a `variations` tree
      (nested `sub_variations` support arbitrary depth) — every variation/sub-variation
      carries `identifying_moves` as the full SAN sequence from move 1 (for direct,
      deterministic matching against a request's `moveHistory`, not just semantic
      retrieval), an `idea` field (honest when a line is mainly a way to sidestep
      main-line theory rather than a deep strategic concept), and optional
      `plans_white`/`plans_black`/`key_ideas`/`traps` only where there's real content —
      no padding for thin lines. Accuracy pass done 2026-08-27 — content is now treated as
      ground truth for ingestion. Future corrections/additions: edit the YAML and re-run
      ingestion; a brand-new opening also needs its `openings` row (Flyway seed today, or
      fold opening-seeding into the ingestion job).
- [x] **Storage.** `V3__add_opening_theory_embeddings.sql` creates the `vector` extension
      and the two tables: `opening_variations` (id, opening_id, `parent_variation_id`
      self-referencing, name, `identifying_moves` jsonb) and `theory_chunks` (opening_id
      NOT NULL — added so opening-level chunks aren't orphaned, `variation_id` nullable =
      opening-level content, `section`, `content`, `embedding vector(1024)` NOT NULL,
      `source`). Embedding dimension fixed at **1024** (`voyage-4-lite` default; it also
      supports 256/512/2048 via Matryoshka). No ANN index — the corpus is a few hundred
      chunks, exact scan wins. JPA entities/repositories added under
      `com.chessmaster.services.theory` (`OpeningVariation`, `TheoryChunk`,
      + repos); the `embedding` column is deliberately unmapped (native SQL handles
      pgvector distance ops in later phases). Local `docker-compose.yml` (in `frontend/`)
      switched to the `pgvector/pgvector:pg16` image so the extension is available in dev.
      Verified 2026-08-27: all three migrations apply cleanly against a live pgvector
      Postgres, Hibernate `validate` passes for the new entities, `mvnw test` green.
- [x] **Ingestion pipeline.** `com.chessmaster.services.theory.ingestion` — a Spring
      `ApplicationRunner` gated by `ingest.enabled=true` that parses the YAML corpus,
      embeds it via Voyage, writes `opening_variations` + `theory_chunks`, logs an
      insert/update/unchanged/delete report, and exits. Run:
      `./mvnw spring-boot:run -Dspring-boot.run.arguments=--ingest.enabled=true
      -Dspring-boot.run.profiles=local`. `TheoryCorpusParser` is the generic chunker
      (`section` = the YAML key; structural keys excluded; unit-tested with no DB/network).
      `V4__add_theory_chunk_content_hash.sql` adds `content_hash` (SHA-256 of the composed
      chunk text) + a `(opening_id, coalesce(variation_id,0), section)` unique index;
      re-runs embed only changed chunks and delete rows the corpus dropped. Variations are
      matched by their full identifying-move sequence (not name), so renames don't
      duplicate. All embedding is done in one pass to minimise requests; the free Voyage
      tier's 3 req/min cap is handled by `voyage.min-request-interval` (default 21s) plus
      429 retry/backoff in `VoyageEmbeddingClient`. Verified 2026-08-27 against the local
      pgvector DB: 9 openings → 59 variations, 90 chunks, all `vector(1024)`; a second run
      re-embeds nothing; a one-field YAML edit re-embeds exactly one chunk. Run against
      the Neon DB on 2026-10-20 after the first trunk-based prod deploy, with the same counts as
      locally. Staging and prod share that DB, so one run covers both. CD does not run ingestion:
      re-run it by hand after any corpus change.
- [x] **Retrieval service.** `com.chessmaster.services.theory.retrieval`:
      `VariationMatcher` finds every variation whose `identifying_moves` is a prefix of the
      request's move history (check/annotation suffixes ignored; most specific = deepest), then
      `TheoryRetrievalService` embeds the question with Voyage and runs a pgvector cosine
      search (`retrieval.top-k`, default 5) over opening-level chunks plus the matched
      variation and its ancestors; `retrieval.variation-slots` (default 3) of the top-k are
      reserved for the line's own chunks so generic opening text can't crowd them out.
      Covered by unit tests plus a `@SpringBootTest` against real pgvector with Voyage
      stubbed. Verified 2026-11-01 against the local pgvector DB: a Najdorf
      + `Be3` line matches English Attack and ranks its `idea` chunk first. Each result
      carries a `Status` (`GROUNDED` = line matched and chunks found, `PARTIAL` = opening known
      but line not matched, `UNKNOWN_OPENING`, `ERROR` = retrieval failed, chat still answers
      ungrounded). A chunk-distance threshold was tried and dropped: Voyage distances sit at
      0.8-1.0 for on-topic and off-topic questions alike (an off-topic pasta question scored
      closer than an on-topic one), so it cannot separate them. Distance is still returned for
      the Phase 3 evals. `GROUNDED` also requires a returned chunk belonging to the matched
      line; opening-level text alone is `PARTIAL`. Query embedding bypasses the ingestion
      throttle/retry (single attempt, `voyage.query-timeout` default 5s), so a Voyage 429 or
      timeout degrades to `ERROR` (ungrounded answer) instead of stalling chat. Open: the
      free tier's 3 req/min still means frequent ungrounded fallbacks until billing is on.
- [x] Update `ClaudeService`'s prompt construction to include retrieved context, with
      instruction to answer from the provided context and say when something isn't covered.
      The system prompt treats the "Theory notes" as source of truth and forbids inventing
      lines; the notes block is worded per status (matched line / loosely related / none
      available, so say it is unchecked). `ChatResponse` now returns `grounding` alongside
      `reply`. Verified end-to-end 2026-11-01 (4 questions: grounded, partial, unknown
      opening, line-matched-but-uncovered question): disclosure and refusal behaved as
      intended. Known limit: `GROUNDED` means "line matched", not "notes answered the
      question"; the reply text covers that case. Before/after comparison against the Phase 1
      prompt is deferred to Phase 3. Follow-ups: frontend notice reading `grounding`, and
      passing the opening's `side` into the prompt (the coach assumed the student was White).

## Phase 3 — Eval harness

Goal: prove the RAG change actually improved answer quality — the "evals, not vibes"
differentiator for interviews.

- [ ] Write a 20-40 question golden set covering a range of openings/variations
      (mix of mainline and obscure, since you already know the right answers).
- [ ] Score retrieval quality (did the right chunks get retrieved?) separately from answer
      quality (LLM-as-judge or manual rubric scoring against the golden answers).
- [ ] Run the eval set against the pre-RAG (Phase 1) and post-RAG (Phase 2) versions and
      keep the comparison — this is the single best interview artifact from the whole
      project.
- [ ] Wire the eval script into CI as a non-blocking report (nice-to-have, not required).

## Phase 4 — Polish / production concerns (optional, time-permitting)

- [ ] Prompt caching for the system prompt / retrieved-context prefix to cut per-request
      cost (Claude-specific feature, directly relevant given the switch).
- [ ] Cost/latency logging per chat request (tokens in/out, retrieval time, model time) —
      basic observability, cheap to add, good talking point.
- [ ] Cache the per-opening variation list used by retrieval (currently loaded and matched
      on every chat request), cleared on ingestion, and pre-normalize `identifying_moves`
      at load time. Only worth it if the corpus grows ~10x or the latency logging above
      shows retrieval time mattering; at 59 variations it is negligible.
- [ ] Citation surfacing in the frontend (which theory chunk/source grounded the answer) —
      nice UX win and a common real-world RAG requirement.

---

## Explicitly out of scope for this project

- Multi-tenant access-controlled retrieval — not applicable, opening theory is public data.
- Fine-tuning — RAG covers the grounding need; no reason to fine-tune here.
