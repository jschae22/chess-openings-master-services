# Chess Opening Master — AI/RAG Roadmap

Portfolio goal: evolve the existing AI coach from a bare LLM call (no grounding) into a
production-quality RAG feature — grounded answers, an eval harness, and a Claude-based
backend — as a demonstrable "AI Engineer" portfolio piece built on an existing Spring Boot
service.

Repo layout:
- `frontend/` — React + TS (Vite), chat UI, calls `/api/chat`
- `backend/` — Spring Boot service, `ChatController` / `ClaudeService`

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
      Cloud Run secret mapping (`VOYAGE_API_KEY=voyage-api-key`), mirroring the Claude key
      setup from Phase 0. **Still open:** create the `voyage-api-key` secret in GCP Secret
      Manager and grant the Cloud Run service account `roles/secretmanager.secretAccessor`
      on it, same as the Claude key's prod setup.
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
- [ ] **Ingestion pipeline.** A small batch job/script that parses the YAML corpus, chunks
      it, embeds it, and writes to the new tables — idempotent/re-runnable so you can demo
      updating theory content later without a full rebuild. Planned approach: generic
      chunker (`section` = the YAML key, so new fields need no code change), and a content
      hash stored per chunk so a re-run only re-embeds chunks whose text actually changed
      (the "update theory without a full rebuild" demo).
- [ ] **Retrieval service.** Given the opening + current FEN/`moveHistory` + user question:
      first find the most specific `opening_variations` row whose `identifying_moves` is a
      prefix of the request's move history (deterministic match, no embedding needed for
      this step), then embed the query and pull top-k relevant `theory_chunks` filtered to
      that opening/variation, injected into the Claude prompt in place of the bare opening
      name.
- [ ] Update `ClaudeService`'s prompt construction to include retrieved context, with
      instruction to answer from the provided context and say when something isn't covered
      (reduces hallucination on obscure lines — worth demoing before/after).

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
- [ ] Citation surfacing in the frontend (which theory chunk/source grounded the answer) —
      nice UX win and a common real-world RAG requirement.

---

## Explicitly out of scope for this project

- Multi-tenant access-controlled retrieval — not applicable, opening theory is public data.
- Fine-tuning — RAG covers the grounding need; no reason to fine-tune here.
