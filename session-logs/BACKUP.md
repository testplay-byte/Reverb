# Session Logs — Backup Convention

> This folder is the durable, version-controlled record of every build session.
> If the dev environment ever dies, we restore from here + `memories/` and resume.

## What lives here

| File | Purpose | Cadence |
|---|---|---|
| `worklog.md` | Append-only chronological log of every task, every subagent result, every orchestrator decision. The full history of how Reverb was built. | Updated after every meaningful chunk of work, pushed to GitHub at the end of each session (and mid-session for long ones). |
| `session-NN-<date>.md` | Per-session summary: what was attempted, what worked, what failed, what's next. Numbered sequentially. | One file per build session, pushed at the end of that session. |
| `decisions/` | Architecture Decision Records (ADRs) — one Markdown file per significant decision, numbered. | Added whenever a non-obvious choice is made (e.g. "why Voyager not navigation-compose", "why Groq not OpenAI as default remote LLM"). |

## Backup discipline

The AI agent working on Reverb commits to this discipline:

1. **Start of session:** `git pull` to get the latest `session-logs/` + `memories/` state.
2. **During session:** append to `worklog.md` after every subagent completes or every meaningful milestone (whichever comes first).
3. **End of session (or every ~30 min):** `git add session-logs/ memories/ docs/ && git commit && git push`.
4. **Never** rewrite history in `worklog.md` — always append. Mistakes get a correction entry, not a deletion.
5. **Never** commit secrets (the `.gitignore` blocks `.env`, `*.key`, `google-services.json`, `secrets.properties`, etc.).

## Restore procedure (if environment is lost)

```bash
git clone https://github.com/testplay-byte/Reverb.git
cd Reverb
cat memories/MEMORY.md          # long-term context: what Reverb is, where we are
ls session-logs/                # see all session summaries
tail -100 session-logs/worklog.md   # see the most recent work
# → resume from the last "next step" noted in the latest session-NN file
```

## Format for session-NN-<date>.md

```markdown
# Session NN — <date> — <one-line goal>

## Goal
<what we set out to do this session>

## What was done
- <concrete step 1>
- <concrete step 2>

## What worked
- <wins>

## What failed / blocked
- <losses + why>

## Files changed
- <path>: <one-line description>

## Next session's first step
- <the immediate next action, so resume is instant>

## Commit(s) pushed
- <sha> <message>
```
