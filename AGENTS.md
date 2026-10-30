# Agent instructions

## Subagent models

- When running on **Cursor**, always use **Auto** or **Composer** as the model for subagents.
- When running on **Claude**, always use **Haiku 5.5** or **Sonnet 5.5** (for complex tasks) as the model for subagents.

## Push / CI

When pushing, always have a subagent wait for CI to succeed and wake you up when it fails.
