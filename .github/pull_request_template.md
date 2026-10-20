## What & why

<!-- One paragraph. Link the ROADMAP item. For cross-repo work, link the sibling PR. -->

## How it was tested

- [ ] `mvn test` passes locally <!-- frontend: npm run lint && npm run test && npm run build -->
- [ ] Manually verified: <!-- what, how -->

## Deploy / migration notes

<!-- Flyway migrations? New secret or env var? Ingestion job needs re-running? "None" if none. -->

## Review checklist (self-review — see cicd-pipeline.md §3)

- [ ] I reviewed my own diff on GitHub before merging
- [ ] `/code-review` was run on this branch
- [ ] `/security-review` was run (if auth, secrets or the API surface changed)
- [ ] Tests were added or updated
- [ ] PR title is a conventional-commit subject (`feat: ...`); it becomes the squash commit
