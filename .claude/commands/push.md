---
description: Push the current branch to origin (with explicit confirmation)
allowed-tools: Bash(git status:*), Bash(git log:*), Bash(git branch:*), Bash(git rev-parse:*), Bash(git push:*), Bash(git remote -v)
---

Push the current branch to its remote.

Steps:
1. Print the current branch: `git rev-parse --abbrev-ref HEAD`.
2. List the remote: `git remote -v`.
3. Show what will be pushed:
   - If an upstream is set: `git log @{u}..HEAD --oneline`.
   - Otherwise: `git log -10 --oneline` and note "no upstream — this will set it on first push".
4. Confirm the working tree is clean (`git status`); warn if there are uncommitted changes but do not stage them yourself.
5. Ask me to confirm before pushing. Wait for explicit "yes" — do not assume.
6. Run `git push -u origin <current-branch>` (or `git push` if upstream already set).
7. **Never** pass `--force` or `--force-with-lease` unless I explicitly ask for it and confirm the target branch.
8. If the push is rejected (non-fast-forward, hook failure, auth error), stop and explain. Do not auto-pull, do not rewrite history, do not retry with force.
