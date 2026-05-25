---
description: Prepare a commit — show diff, draft a message, stage and commit with your review
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git add:*), Bash(git commit:*), Bash(git rev-parse:*), Bash(git branch:*), Read, Grep
---

Prepare a commit for the current local changes.

Steps:
1. Run `git status` and `git diff --stat` to see what changed.
2. Run `git diff` and `git diff --staged` to read the actual content.
3. Run `git log -5 --oneline` to match the repo's message style.
4. Scan for sensitive files (`.env`, anything matching `*secret*`, `*credentials*`, `*.pem`, `*.key`). If any are in the change set, refuse to stage them and call them out.
5. Draft a commit message:
   - Subject line under 70 chars, imperative mood ("Fix X", "Add Y").
   - Blank line, then body that explains *why* (1–3 short paragraphs).
6. Show me the drafted message and the explicit list of files that will be staged. Wait for my approval before staging.
7. Once I approve, stage with specific file paths (`git add path/one path/two`) — never `git add -A` or `git add .`.
8. Run `git commit -m "..."` and confirm success with `git status`.

Do not push. Use `/push` separately when I'm ready to share the commit.

Never bypass hooks (`--no-verify`), never amend a previous commit, and never sign with my Co-Authored-By unless I ask.
