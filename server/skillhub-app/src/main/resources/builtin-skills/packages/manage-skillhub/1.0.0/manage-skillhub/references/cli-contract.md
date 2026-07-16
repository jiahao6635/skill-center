# SkillHub CLI contract

Always add `--json`. Successful output is one JSON object on stdout; failed output is one JSON object on stderr.

## Read commands

```text
skillhub search [query]
skillhub skill show <@namespace/slug>
skillhub skill versions <@namespace/slug>
skillhub download <@namespace/slug> --version <version> --output <path>
skillhub review list --namespace <namespace> --status pending
skillhub review submissions --namespace <namespace>
skillhub review show <review-id>
skillhub review download <review-id> --output <path>
skillhub external search [query]
skillhub external show <slug>
```

## Mutations

```text
skillhub publish <path> --namespace <namespace> --visibility <visibility> --dry-run
skillhub publish <path> --namespace <namespace> --visibility <visibility> --confirm-warnings <warning-digest>
skillhub skill archive|unarchive <@namespace/slug>
skillhub skill submit-review|withdraw-review|confirm-publish <@namespace/slug> --version <version>
skillhub skill rerelease <@namespace/slug> --version <source> --target-version <target>
skillhub skill delete-version <@namespace/slug> --version <version> --confirm <@namespace/slug@version>
skillhub review approve <review-id> --confirm <@namespace/slug@version>
skillhub review reject <review-id> --comment <reason> --confirm <@namespace/slug@version>
skillhub external import <slug> --version <version> --namespace <namespace> --visibility private +  --confirm-warnings --warning-digest <warning-digest>
```

Use server-provided warning digests, package hashes, task versions, and idempotency keys exactly as returned. Never fabricate them.

## Exit handling

- `2`: authorization failure. Reauthorize only for the same target namespace and required scopes.
- `3`: network or throttling. Reads may retry with backoff; writes may retry only with the original idempotency key.
- `6`: validation failed. Show errors and stop.
- `7`: explicit confirmation required. Ask the user and wait.
- `8`: state, content, or idempotency conflict. Refresh and stop.
- `9`: target no longer exists. Report it and stop.

Reject whole-Skill hard delete, hide/unhide, yank, user management, and batch review requests.
