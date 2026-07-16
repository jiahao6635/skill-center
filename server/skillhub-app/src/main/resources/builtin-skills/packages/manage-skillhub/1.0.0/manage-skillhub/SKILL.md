---
name: manage-skillhub
description: Manage skills and reviews in an authorized SkillHub registry through the official skillhub CLI. Use when a user asks an agent to search, inspect, download, publish, archive, restore, submit or withdraw a review, rerelease or delete a version, import from an enabled external catalog, or approve or reject a single review task.
metadata:
  version: 1.0.0
---

# Manage SkillHub

Use only the official `skillhub` CLI with `--json`. Never call page APIs with curl and never read or print credential files or raw tokens.

Read [references/cli-contract.md](references/cli-contract.md) before executing a mutation or interpreting an error.

## Workflow

1. Run `skillhub version --json` and require contract version 1 or newer. Do not self-update without a separate user request.
2. Run `skillhub whoami --json`. If authorization is missing or insufficient, request only the target namespace and necessary scopes through `skillhub login`.
3. Use read commands directly and report the canonical coordinate, version, and status.
4. Before any write, show the exact target and intended transition.
5. Publish only after a dry run. Stop on errors. If warnings are returned, show them and ask for explicit confirmation before reusing the returned digest.
6. Before approving or rejecting, fetch the latest review detail and show review ID, coordinate, version, submitter, scan state, and comment. Obtain a fresh confirmation for exactly one review.
7. Before deleting a version, fetch current version details and require confirmation of the exact `@namespace/slug@version` string.
8. On a mutation network failure, retry only with the original idempotency key. On a state conflict, refresh and stop; never continue automatically.

## Mandatory stops

- Never approve or reject multiple reviews without separate confirmation for each item.
- Never infer approval from a prior blanket instruction such as “approve all”.
- Never hard-delete an entire remote Skill.
- Never perform hide, unhide, yank, user administration, or unattended batch review.
- Never expand the Token namespace to bypass a permission failure.

## Completion report

Report the canonical coordinate, version, action, before/after status, and request ID. Do not include secrets.
