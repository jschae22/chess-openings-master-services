# CI/CD Pipeline Policy

Single source of truth for how code moves from an idea to production in Chess Opening Master.
It covers **both** repositories. The frontend repo points here.

| | Repo | Prod service | Staging service |
|---|---|---|---|
| Backend | `jschae22/chess-openings-master-services` | `chess-opening-master-services` | `chess-opening-master-services-staging` |
| Frontend | `jschae22/chess-opening-master` | `chess-opening-master` | `chess-opening-master-staging` |

Status legend: **Policy** = decided. **Proposed** = drafted, needs sign-off. **Not yet enforced** = policy is
decided but GitHub or the workflows don't enforce it yet (see [Rollout](#rollout)).

---

## 1. Branching model — trunk-based (Policy)

- `main` is the only long-lived branch. It is always releasable.
- All work happens on short-lived branches cut from `main` and merged back via pull request.
- Nothing is committed directly to `main`. The long-lived `dev` branch is retired (see Rollout). Combined testing uses
  throwaway branches (see [Integration testing](#integration-testing-policy)).

```
main ──●────────●──────────●──▶  (every merge deploys)
        \      /  \        /
         feat-…    fix-…
```

### Branch naming (Policy)

Format: `<type>-<scope-words>`, lowercase, words separated by dashes.

| Type | Use for |
|---|---|
| `feat` | New user-visible behaviour or API |
| `fix` | Bug fix |
| `chore` | Tooling, dependencies, config, CI |
| `docs` | Documentation only |
| `refactor` | Behaviour-preserving restructuring |
| `test` | Adding or fixing tests only |

Rules:
- The name states the scope: someone reading it should know what the branch changes.
- 3–6 words after the type. No ticket numbers, dates or initials.
- Good: `feat-opening-search-filter`, `fix-board-flip-on-undo`, `chore-add-dependabot-config`
- Bad: `feature/stuff`, `josh-wip`, `fix-bugs`, `feat-ui`

### Scope and lifetime (Policy)

- One feature or ROADMAP item per branch. If the description needs "and", consider two branches.
- Aim to merge within a few days. A branch open longer than about a week should be split or rebased on `main`.
- Branch from an up-to-date `main`. Rebase on `main` before opening the PR if it has moved.

### Cross-repo changes (Policy)

When one feature touches both repos (e.g. a new API plus UI):
- Use the **same branch name** in both repos.
- Each PR links the other in its description.
- Merge the backend first when the change is backward compatible, otherwise coordinate the deploys.

### Integration testing (Policy)

There is no long-lived integration branch. When several independent features or fixes should be tested together
before release, use a throwaway branch:

1. **Create it from `main`:** `git switch -c integration-<scope-words> main`, e.g. `integration-search-and-coach`.
2. **Merge the feature branches into it:** `git merge feat-opening-search-filter`, and so on. Features that depend on
   each other are not combined this way: merge the first to `main` through its PR, then branch the second from `main`.
3. **Test locally.** If the deployed environment is needed, push the branch and deploy it to staging (below).
4. **Delete it** when done. An `integration-*` branch is never merged into `main` and never opened as a PR. Each
   feature still reaches `main` through its own PR.

**Deploying an integration branch to staging:** run CD manually from the branch. Any ref other than `main` deploys to
the staging Cloud Run service.

```bash
git push -u origin integration-search-and-coach
gh workflow run cd.yml --ref integration-search-and-coach
```

Notes:
- A staging deploy is manual and temporary. Redeploying from another branch overwrites it.
- **Staging and prod share one Neon database** (confirmed 2026-10-20: both Cloud Run services read the same
  `NEON_DB_URL` secret). A Flyway migration run by a staging deploy therefore also changes the prod schema, and
  prod's running code must tolerate it. Keep migrations additive and backward compatible (add columns and tables;
  never drop or rename in the same release that stops using them). Test migrations locally against pgvector first.
  Giving staging its own Neon branch or database is a future improvement.
- For a large feature that cannot be merged within a week, prefer merging it to `main` behind a feature flag, off in
  prod, over a long-lived branch.

---

## 2. Commits and pull requests

### Commits (Policy)
- Conventional-commit style subjects, using the same types as branches: `feat: add opening search filter`.
- Imperative mood, present tense, ~72 characters or less.

### Pull requests (Policy)
- The PR title is a conventional-commit subject. It becomes the squash commit message on `main`.
- Open the PR only when coding and testing are complete. Use a draft PR for early feedback.
- Fill in the PR template: what and why, how it was tested, deploy or migration notes, and the review checklist.
- Link the ROADMAP item and, for cross-repo work, the sibling PR.

### Merging (Policy)
- **Squash merge only.** One feature becomes one commit on `main`.
- Merge and rebase merges are disabled in repo settings.
- Branches are deleted automatically after merge.

---

## 3. Review policy (Policy — Option A, solo maintainer)

GitHub cannot count an author's own approval, so required approvals are set to **0**. Quality gates that
GitHub *can* enforce are mandatory; the review step is a documented checklist.

**Enforced by GitHub:**
- A PR is required to change `main`. Direct pushes are blocked.
- All required status checks pass, on a branch that is up to date with `main`.
- All review conversations are resolved.
- Linear history. Force pushes and branch deletion are blocked on `main`.
- No bypass: the rules apply to admins, including the repo owner.

**Checklist in the PR template (not enforced):**
- [ ] I reviewed my own diff on GitHub before merging.
- [ ] `/code-review` was run on the branch (`high` for most changes).
- [ ] `/security-review` was run if the change touches auth, secrets or the API surface.
- [ ] Tests were added or updated for the change.

**Revisit** this policy when a second contributor joins. Then set required approvals to 1, add
CODEOWNERS enforcement and review per contributor.

---

## 4. Continuous integration (Policy)

CI runs on every push to a non-`main` branch and on every PR targeting `main`, so a feature branch gets
feedback before a PR exists.

| Repo | Required check (job name) | Steps |
|---|---|---|
| Backend | `Test & Build` | `mvn test`, `mvn package` (Java 21) |
| Frontend | `Lint, Test, Build` | `npm ci`, `npm run lint`, `npm run test`, `npm run build` (Node 22) |

Rules:
- The required checks are exactly the job names above. Renaming a job requires updating the branch ruleset.
- A failing check blocks the merge. Don't merge around a red CI; fix it or fix the check.
- Planned additions (not required yet): backend formatting (Spotless), coverage report (JaCoCo), frontend
  ESLint and Prettier.

---

## 5. Continuous delivery (Policy)

- Every merge to `main` builds a Docker image tagged with the commit SHA and deploys it to Cloud Run.
- **Staging vs prod (Policy):** `main` deploys straight to prod after CI passes. The `dev` → staging flow is
  retired along with the `dev` branch. Revisit once the app has real users: the alternative is deploying `main` to
  staging and promoting to prod through a GitHub Environment with a manual approval. The staging Cloud Run services
  stay available for manual `workflow_dispatch` deploys of a branch.
- **Triggers:** `cd.yml` runs on every push to `main` (→ prod) and on manual `workflow_dispatch`. A manual run from any
  other ref deploys to staging.
- **Smoke test (Policy, live in both repos):** after deploying, CD polls the new Cloud Run URL (10 attempts, 6s apart)
  and fails the run unless every check returns HTTP 200. Backend: `/actuator/health` and `/api/openings`. Frontend:
  `/` and `/api/openings` (through the nginx proxy, which catches a broken `BACKEND_URL`). A failed smoke test fails the
  run but does not roll back the revision: follow the rollback steps below.
- **Docs-only merges don't deploy:** CD ignores pushes that change only `**.md`, CODEOWNERS or the PR template.
- Every prod deploy creates a GitHub Release with generated notes, for traceability and rollback.
- **Rollback:** redeploy the previous image SHA on Cloud Run, or revert the squash commit through a new PR.

### Secrets (Policy)
- Deploy credentials and API keys live in GitHub Actions secrets (CI/CD) or GCP Secret Manager (runtime).
- Never commit secrets or `.env` files. Secret scanning and push protection should be enabled on both repos.

---

## 6. Repository settings (target state)

Applied to **both** repos, using a ruleset on `main`. Backend: applied 2026-10-20 as ruleset `main-protection`.
Frontend: applied 2026-10-20, same ruleset name, required check `Lint, Test, Build`.

- [x] Require a pull request, with 0 required approvals
- [x] Require status checks (job names in §4) and require branches to be up to date
- [x] Require conversation resolution
- [x] Require linear history
- [x] Block force pushes and deletion
- [x] No bypass actors
- [x] Allow squash merge only
- [x] Automatically delete head branches
- [x] Secret scanning and push protection enabled
- [x] Dependabot security updates enabled

Supporting files in each repo:
- [x] `.github/pull_request_template.md`
- [x] `.github/CODEOWNERS`
- [x] `.github/dependabot.yml` (maven or npm, plus github-actions)

---

## 7. Day-to-day workflow

```bash
git switch main && git pull
git switch -c feat-opening-search-filter

# ... code, test, commit ...

git push -u origin feat-opening-search-filter
gh pr create --base main --fill      # title: "feat: add opening search filter"

# before merging: self-review the diff, run /code-review, wait for CI to go green
gh pr merge --squash --delete-branch
# → image built and deployed per §5
```

---

## 8. Dependency updates (Policy)

Dependabot runs weekly (Mondays, time chosen by GitHub) in both repos and also re-scans whenever `dependabot.yml`
changes. Security updates are separate: they arrive immediately, one PR each, outside the groups.

- **Grouping:** GitHub Actions bumps arrive as one PR. Maven (backend) and npm (frontend) minor and patch bumps
  arrive as one PR. Major bumps get their own PR.
- **Reviewing:** CI must pass. An Actions bump is only really tested by the next prod deploy, so watch its smoke test.
- **Major versions of frameworks** are migrations: do them on a `chore-upgrade-<name>` branch, not by merging the
  Dependabot PR. The Spring Boot parent major bump is ignored in `dependabot.yml` for this reason.
- A Dependabot PR that someone else edited (including "Update branch") cannot be rebased by Dependabot. Comment
  `@dependabot recreate` instead.

---

## Rollout

Order of operations to move from today's state to the policy above. Backend first, then mirror to the frontend.

Backend progress is marked below; the frontend follows the same steps afterwards.

1. [x] Commit the uncommitted backend changes on a feature branch (`feat-theory-ingestion-pipeline`).
2. [x] Reconcile `dev` into `main`. Backend done: `main` carries all of `dev`, with CD paused. Frontend done the same way.
3. [x] Update `ci.yml`: feature-branch and PR triggers, plus a pgvector service so the context test can run.
4. [x] Update `cd.yml` to deploy from `main` only (push trigger on `main` → prod). Backend and frontend done.
5. [x] Add the PR template, CODEOWNERS and Dependabot config. Backend and frontend done.
6. [x] Enable the `main` ruleset and repo settings from §6. Backend and frontend done (ruleset `main-protection`, 2026-10-20).
7. [x] Delete the `dev` branch, locally and on GitHub. Backend and frontend done.
8. [ ] Add the smoke test (done in both repos) and the release-tag step (pending: GitHub Release on each prod deploy).
9. [x] Remove the stale `GEMINI_API_KEY` secret from the frontend repo. Done 2026-10-20.

The original implementation plan (`pipeline-setup.md`, written for the retired `dev` flow) was removed once the
rollout was done. Its snippets remain in git history; the items it still held are in the [Backlog](#backlog).

## Backlog

Not-yet-done improvements carried over from the original plan. None is required by the policy above.

- **Pin GitHub Actions to commit SHAs** (supply chain), with a version comment. The `github-actions` Dependabot
  ecosystem then keeps the pins current. Both repos.
- **Readable CI logs (backend):** replace `-q` on the `mvn` calls with `--batch-mode --no-transfer-progress`, and upload
  `target/surefire-reports` as an artifact on failure.
- **Spotless (backend):** `spotless-maven-plugin` with google-java-format and unused-import removal, `check` bound to
  the build. Run `spotless:apply` once on its own branch, since it reformats the whole tree.
- **JaCoCo (backend):** `prepare-agent` + `report`, with a PR coverage comment in report-only mode at first; raise the
  threshold as tests are added.
- **Frontend ESLint + Prettier** check on top of the existing `tsc --noEmit` lint script.
- **Release tag on prod deploy:** the `release` job (needs `deploy`, `main` only, `contents: write`,
  `gh release create ... --generate-notes`). Same as rollout step 8's pending half.
- **Manual ingestion workflow (backend):** an `ingest.yml` `workflow_dispatch` job that runs
  `spring-boot:run -Dspring-boot.run.arguments=--ingest.enabled=true`, replacing the by-hand run after a corpus
  change. Needs `NEON_DB_URL`, `VOYAGE_API_KEY` and `CLAUDE_API_KEY` copied into GitHub Actions secrets (they live
  only in GCP Secret Manager today). Staging and prod share one database, so there is no environment choice to make.
- **Canary on prod:** deploy with `--no-traffic --tag candidate`, smoke-test the tagged URL, then shift traffic.
  Defer until there is real traffic.
- **Publish the `/api/chat` contract** (OpenAPI via springdoc) so the frontend has something concrete to build against.
- **CLAUDE.md:** add one to the backend (none exists) and fix the frontend's, which still references Gemini.

## Open decisions

- Spring Boot 4 migration (`chore-upgrade-spring-boot-4`), and the frontend vite 8, plugin-react 6 and motion 14
  majors. Each is tracked as its own branch.
- Frontend CD hardcodes `BACKEND_URL` to the prod backend, so a staging frontend deploy talks to the prod backend.
  Pick the URL by ref, as the backend workflow does.
- Add `docker` to `dependabot.yml` if the base images should be tracked.
- Whether to add Spotless, JaCoCo and frontend ESLint/Prettier to the required checks, and when.
