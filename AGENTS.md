# Agent instructions

## Subagent models

- When running on **Cursor**, always use **Auto** or **Composer** as the model for subagents.
- When running on **Claude**, always use **Haiku 5.5** or **Sonnet 5.5** (for complex tasks) as the model for subagents.

## Markdown files

Never edit original `.md` files that came with the upstream project (for example `README.md` and `Privacy.md`). New agent-owned docs such as this file are fine to change.

## Push / CI

When pushing, always have a subagent wait for CI to succeed and wake you up when it fails.
