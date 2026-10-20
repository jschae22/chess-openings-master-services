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

## 4. Continuous integration (Proposed)

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

## 5. Continuous delivery (Proposed)

- Every merge to `main` builds a Docker image tagged with the commit SHA and deploys it to Cloud Run.
- **Staging vs prod (Policy):** `main` deploys straight to prod after CI passes. The `dev` → staging flow is
  retired along with the `dev` branch. Revisit once the app has real users: the alternative is deploying `main` to
  staging and promoting to prod through a GitHub Environment with a manual approval. The staging Cloud Run services
  stay available for manual `workflow_dispatch` deploys of a branch.
- **CD is currently paused:** `cd.yml` is `workflow_dispatch` only. The `push` trigger on `main` returns as the final
  rollout step, after the ruleset is on.
- After deploy, a smoke test must pass: the backend health endpoint, and `/` returning 200 on the frontend.
  This needs `spring-boot-starter-actuator` in the backend.
- Every prod deploy creates a GitHub Release with generated notes, for traceability and rollback.
- **Rollback:** redeploy the previous image SHA on Cloud Run, or revert the squash commit through a new PR.

### Secrets (Policy)
- Deploy credentials and API keys live in GitHub Actions secrets (CI/CD) or GCP Secret Manager (runtime).
- Never commit secrets or `.env` files. Secret scanning and push protection should be enabled on both repos.

---

## 6. Repository settings (target state)

Applied to **both** repos, using a ruleset on `main`. Backend: applied 2026-10-20 as ruleset `main-protection`.
Frontend: pending.

- [ ] Require a pull request, with 0 required approvals
- [ ] Require status checks (job names in §4) and require branches to be up to date
- [ ] Require conversation resolution
- [ ] Require linear history
- [ ] Block force pushes and deletion
- [ ] No bypass actors
- [ ] Allow squash merge only
- [ ] Automatically delete head branches
- [ ] Secret scanning and push protection enabled
- [ ] Dependabot security updates enabled

Supporting files in each repo:
- [ ] `.github/pull_request_template.md`
- [ ] `.github/CODEOWNERS`
- [ ] `.github/dependabot.yml` (maven or npm, plus github-actions)

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

## Rollout

Order of operations to move from today's state to the policy above. Backend first, then mirror to the frontend.

Backend progress is marked below; the frontend follows the same steps afterwards.

1. [x] Commit the uncommitted backend changes on a feature branch (`feat-theory-ingestion-pipeline`).
2. [x] Reconcile `dev` into `main`. Backend done: `main` carries all of `dev`, with CD paused. Frontend pending.
3. [x] Update `ci.yml`: feature-branch and PR triggers, plus a pgvector service so the context test can run.
4. [ ] Update `cd.yml` to deploy from `main` only (re-enable the push trigger, `main` → prod). Backend: next.
5. [x] Add the PR template, CODEOWNERS and Dependabot config. Backend done.
6. [x] Enable the `main` ruleset and repo settings from §6. Backend done (ruleset `main-protection`, 2026-10-20).
7. [x] Delete the `dev` branch, locally and on GitHub. Backend done.
8. [ ] Add the smoke test and release steps.
9. [ ] Remove the stale `GEMINI_API_KEY` secret from the frontend repo.

Detailed implementation notes and snippets for these steps are in `pipeline-setup.md`. Fold that file into this
one, or retire it, once the rollout completes.

## Open decisions

- Whether to add Spotless, JaCoCo and frontend ESLint/Prettier to the required checks, and when.
