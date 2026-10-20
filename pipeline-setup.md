# CI/CD Pipeline Setup

Steps to bring both repos up to an industry-standard feature-branch → PR → review → merge → deploy
workflow. Work through the phases in order; each is independently shippable.

Applies to **both** repos (near-identical setup today):

| | Repo | Prod service | Staging service |
|---|---|---|---|
| Backend | `jschae22/chess-openings-master-services` | `chess-opening-master-services` | `chess-opening-master-services-staging` |
| Frontend | `jschae22/chess-opening-master` | `chess-opening-master` | `chess-opening-master-staging` |

Do the backend first, confirm it works end-to-end, then mirror to the frontend.

---

## Current state (baseline)

- `main` + long-lived `dev` in both repos.
- CI (`mvn test`/`npm` lint+test+build) runs **only on `pull_request`** to `main`/`dev`.
- CD runs on **push** to `main`/`dev` → Docker build → Cloud Run (`dev`→staging, `main`→prod).
- Work is committed **directly to `dev`** — so CI never runs before a staging deploy. No gate exists.
- No branch protection, CODEOWNERS, PR template, or Dependabot.

**Target workflow**

```
feature/<slug>  ──PR──▶  dev  ──(auto)──▶  staging Cloud Run
                          │
                          └──PR──▶  main  ──(auto)──▶  prod Cloud Run  + release tag
```

Nothing reaches `dev` or `main` except via a reviewed, CI-passing PR.

---

## Phase A — Make CI an actual gate (highest value)

### A1. Add a `push` trigger so CI runs on feature branches

`.github/workflows/ci.yml` — change the trigger block:

```yaml
on:
  pull_request:
    branches: [main, dev]
  push:
    branches-ignore: [main, dev]   # feature branches get fast feedback; main/dev covered by PR runs
```

- [ ] Backend `ci.yml`
- [ ] Frontend `ci.yml`

### A2. Enable branch protection (GitHub UI)

Repo → **Settings → Branches → Add branch ruleset** (or classic "Branch protection rules").
Create one rule for `main` and one for `dev` (or a ruleset targeting both):

- [ ] Require a pull request before merging
  - [ ] Require approvals: **1** (you approve your own via a second account, or accept "0" + rely on the review agents — see ROADMAP note; 1 looks more professional if you have a second account)
  - [ ] Dismiss stale approvals on new commits
- [ ] Require status checks to pass before merging
  - [ ] Require branches to be up to date before merging
  - [ ] Required check: `Test & Build` (backend) / `Lint, Test, Build` (frontend) — the job `name:`
- [ ] Require linear history
- [ ] Require conversation resolution before merging
- [ ] Do not allow bypassing the above settings (or: allow for yourself only, knowingly)
- [ ] Block force pushes

Do this for **both repos**, **both branches** (`main`, `dev`).

### A3. Gate prod CD on CI

Right now `cd.yml` fires on any push to `main`. With A2 in place, `main` only moves via a merged PR
whose CI already passed — that is sufficient. Optionally make it explicit by switching CD to a
`workflow_run` trigger:

```yaml
on:
  workflow_run:
    workflows: [CI]
    types: [completed]
    branches: [main, dev]

jobs:
  deploy:
    if: ${{ github.event.workflow_run.conclusion == 'success' }}
```

Keep it simple (push trigger) unless you want the belt-and-braces. Note: `workflow_run` uses the
default-branch copy of the workflow, which is a gotcha when editing it.

- [ ] Decide: rely on branch protection (simpler) **or** switch to `workflow_run`
- [ ] Apply to both repos if switching

---

## Phase B — PR hygiene & repo scaffolding

### B1. Pull request template

`.github/pull_request_template.md`:

```markdown
## What & why

<!-- One paragraph. Link the ROADMAP item if applicable. -->

## How it was tested

- [ ] `mvn test` green locally           <!-- frontend: npm run lint && npm run test && npm run build -->
- [ ] Manually verified: <!-- what, how -->

## Deploy / migration notes

<!-- Flyway migrations? New secret/env var? Ingestion job needs re-running? "None" if none. -->

## Screenshots / logs

<!-- optional -->
```

- [ ] Backend
- [ ] Frontend

### B2. CODEOWNERS

`.github/CODEOWNERS`:

```
* @jschae22
```

Enables automatic review-request on every PR. Add path-specific owners later if you want.

- [ ] Backend
- [ ] Frontend

### B3. Dependabot

`.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: maven          # frontend: npm
    directory: "/"
    schedule: { interval: weekly }
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: "/"
    schedule: { interval: weekly }
```

- [ ] Backend (`maven` + `github-actions`)
- [ ] Frontend (`npm` + `github-actions`)

### B4. Pin GitHub Actions to commit SHAs (supply-chain)

Replace `uses: actions/checkout@v4` etc. with the pinned SHA + version comment. Dependabot
(`github-actions` ecosystem, B3) will then keep them current via PRs.

- [ ] Backend workflows
- [ ] Frontend workflows

---

## Phase C — CI quality checks

### C1. Java formatting/lint gate (backend)

Add Spotless to `pom.xml`:

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.43.0</version>
  <configuration>
    <java>
      <googleJavaFormat/>
      <removeUnusedImports/>
    </java>
  </configuration>
  <executions>
    <execution><goals><goal>check</goal></goals></execution>
  </executions>
</plugin>
```

- [ ] Run `mvn spotless:apply` once, commit the reformatted tree on its own branch
- [ ] `mvn test` now runs `spotless:check` automatically (bound to `compile`); CI fails on unformatted code
- [ ] Frontend already covered by `npm run lint` (tsc --noEmit) — consider adding ESLint + Prettier check

### C2. Coverage report (backend)

Add JaCoCo to `pom.xml` (`prepare-agent` + `report` goals), then in `ci.yml`:

```yaml
      - name: Coverage summary
        uses: madrapps/jacoco-report@v1.7.1
        with:
          paths: target/site/jacoco/jacoco.xml
          token: ${{ secrets.GITHUB_TOKEN }}
          min-coverage-overall: 0     # report-only for now; raise later
```

- [ ] Add JaCoCo plugin
- [ ] Add PR comment step
- [ ] Backfill tests toward a real threshold over time (currently 2 test files)

### C3. Separate, readable CI steps

- [ ] Drop `-q` from the `mvn` calls in CI so failures are legible in the Actions log
- [ ] Add `--batch-mode --no-transfer-progress` instead
- [ ] Upload `target/surefire-reports` as an artifact on failure

---

## Phase D — CD hardening

### D1. Post-deploy smoke test

After the `deploy-cloudrun` step, add:

```yaml
      - name: Smoke test
        run: |
          URL=$(gcloud run services describe ${{ steps.service.outputs.name }} \
            --region ${{ env.REGION }} --format 'value(status.url)')
          for i in $(seq 1 10); do
            code=$(curl -s -o /dev/null -w '%{http_code}' "$URL/actuator/health" || true)
            [ "$code" = "200" ] && echo "healthy" && exit 0
            sleep 6
          done
          echo "health check failed" && exit 1
```

- [ ] Confirm a health endpoint exists (`spring-boot-starter-actuator` → `/actuator/health`); add if missing
- [ ] Backend `cd.yml`
- [ ] Frontend `cd.yml` (curl `/` for 200)

### D2. Release tag + GitHub Release on prod deploy

Add a job that runs only for `main`:

```yaml
  release:
    needs: deploy
    if: github.ref_name == 'main'
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v4
      - run: |
          gh release create "deploy-$(date +%Y%m%d-%H%M%S)" \
            --target ${{ github.sha }} \
            --generate-notes
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- [ ] Backend
- [ ] Frontend

### D3. Canary on prod (optional)

Switch the prod deploy to `--no-traffic --tag candidate`, smoke-test the tagged revision URL, then
`gcloud run services update-traffic --to-latest`. Only worth it once there's real traffic.

- [ ] Decide whether to bother now (probably defer)

---

## Phase E — Operational jobs

### E1. Manual ingestion job (backend)

The theory ingestion pipeline must be run against Neon after a corpus change (ROADMAP Phase 2 open
item). Replace the by-hand run with a `workflow_dispatch` job.

`.github/workflows/ingest.yml`:

```yaml
name: Theory Ingestion
on:
  workflow_dispatch:
    inputs:
      environment:
        type: choice
        options: [staging, prod]

jobs:
  ingest:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin, cache: maven }
      - name: Run ingestion
        env:
          DB_URL:          ${{ secrets.NEON_DB_URL }}
          CLAUDE_API_KEY:  ${{ secrets.CLAUDE_API_KEY }}
          VOYAGE_API_KEY:  ${{ secrets.VOYAGE_API_KEY }}
        run: mvn -B spring-boot:run -Dspring-boot.run.arguments=--ingest.enabled=true
```

- [ ] Add the workflow
- [ ] Add the required secrets to the repo (GitHub → Settings → Secrets → Actions) — these currently
      live only in GCP Secret Manager
- [ ] Decide: separate staging vs prod Neon URLs, or one DB
- [ ] Run it once against prod after the next deploy

---

## Phase F — Cross-repo coordination

- [ ] When a change spans both repos (e.g. the Gemini→Claude migration did), cross-link the two PRs
      in their descriptions
- [ ] Consider publishing the backend's `/api/chat` contract (OpenAPI via springdoc) so the frontend
      has something concrete to build against
- [ ] Add a root `CLAUDE.md` to the backend (none exists) and fix the frontend's (in `claude_env/`,
      still references Gemini) — see separate CLAUDE.md task

---

## Day-to-day workflow once set up

```bash
git switch dev && git pull
git switch -c feature/retrieval-service

# ... work, commit ...

git push -u origin feature/retrieval-service
gh pr create --base dev --fill

# review before merging:
#   /code-review high          (in Claude Code, on the branch or PR)
#   /security-review           (if auth/secrets/API surface touched)
#   /code-review --comment <PR#>   to leave inline PR comments

gh pr merge --squash --delete-branch   # after CI green + review
# → auto-deploys to staging

# promote to prod when staging looks good:
gh pr create --base main --head dev --fill --title "release: <summary>"
gh pr merge --merge          # → auto-deploys to prod + release tag
```

---

## Progress tracker

- [ ] Phase A — CI gate + branch protection (backend)
- [ ] Phase A — CI gate + branch protection (frontend)
- [ ] Phase B — PR template / CODEOWNERS / Dependabot / SHA pinning (both)
- [ ] Phase C — Spotless + JaCoCo + CI legibility (backend), ESLint/Prettier (frontend)
- [ ] Phase D — smoke test + release tags (both)
- [ ] Phase E — ingestion workflow_dispatch (backend)
- [ ] Phase F — cross-repo coordination + CLAUDE.md
