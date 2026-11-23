# Agent workflow

Rules for agents delivering Fytorium waves. They keep the quality bar and cut the waiting.

## Checks

| When | Command | Typical scope |
| --- | --- | --- |
| Each Android fix | `./scripts/gate android --tests '*ChangedScreenTest'` | changed test classes, screenshots verified |
| Each server fix | `./scripts/gate server tests/api/<touched>.test.ts` | typecheck, unit tests, touched API files |
| Release candidate | `./scripts/gate full`, or the pull request's CI | everything except emulator e2e |
| Once per wave | `./scripts/stack up`, then `E2E_LOGIN=1 ./scripts/android e2e` | emulator e2e on the candidate |

- The gate stops at the first failure and exits with its status. Read `GATE FAIL` and fix that before running anything else.
- `./scripts/android test` verifies screenshots against the committed baselines. Record only for an intended visual change: `ANDROID_RECORD_SCREENSHOTS=1 ./scripts/android screenshots`, then review the image diff before committing it.
- Run a full check once per candidate, not once per fix. CI on the pull request counts as the full check.
- Agents may push fytorium feature branches and open draft pull requests so CI runs. Pushing to main and merging still need the owner's yes.

## Flaky tests

- Rerun only the failing test: `./scripts/android test --tests 'Class.method'`. Do not rerun whole classes in a loop.
- A test that fails once in three runs is flaky. Fix the wait condition in the same wave, or mark it ignored with a linked issue and say so in the PR.

## Waiting for long jobs

Start long jobs in the background with an exit marker and wait on the marker, never on a fixed `sleep`:

```sh
nohup sh -c './scripts/gate full; echo EXIT=$?' > gate.log 2>&1 &
until grep -q '^EXIT=' gate.log; do sleep 15; done; tail -n 20 gate.log
```

## Reviews

- Review the frozen diff after the per-fix check passes, before the full check.
- Code review: at most 2 rounds. Nits never start a new round. A re-review covers only what changed.
- Council: an item passes at 9.5. Minor (S3) findings go to the next wave's backlog and do not block. At most 3 rounds per wave. Later rounds re-check open findings and changed items only.
- If something is still open after the round cap, stop and ask the owner.

## Threads

- One orchestrator thread per wave. Start it from a handoff file with the goal, standing approvals, candidate commit, open findings and backlog.
- The orchestrator never waits on a check itself. Child agents run checks and commit in their own worktrees.
