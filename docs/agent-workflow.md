# Agent workflow

Rules for agents delivering Fytorium waves. They keep the quality bar and cut the waiting.

## Checks

| When | Command | Covers |
| --- | --- | --- |
| Each Android fix | `./scripts/gate android --tests '*ChangedScreenTest'` | matching unit tests, their screenshots verified |
| Each server fix | `./scripts/gate server tests/api/<touched>.test.ts` | typecheck, unit tests, the touched API files |
| Each pull request push | CI (`android.yml`, `image.yml`) | everything except browser and emulator tests |
| Release candidate | green CI on the candidate commit, plus `./scripts/gate browser` | adds the browser tests CI lacks |
| No CI available | `./scripts/gate full` | everything except emulator e2e |
| Once per wave | `./scripts/stack up`, then `E2E_LOGIN=1 ./scripts/android e2e` | emulator e2e on the candidate |

- The gate stops at the first failure and exits with its status. Read `GATE FAIL` and fix that before running anything else.
- `./scripts/android test` verifies screenshots against the committed baselines. Record only for an intended visual change: `ANDROID_RECORD_SCREENSHOTS=1 ./scripts/android screenshots`, then review the image diff before committing it. When CI fails on screenshots, the `roborazzi-diffs` artifact holds the compare images.
- Run the full check once per candidate, not once per fix.
- Agents may push fytorium feature branches and open draft pull requests so CI runs. Pushing to main and merging still need the owner's yes.

## Flaky tests

- Rerun only the failing test, and force it to run: `./scripts/android test --tests 'Class.method' --rerun`. Without `--rerun` Gradle may report an earlier result as up to date.
- A test that fails once in three runs is flaky. Fix the wait condition in the same wave, or mark it ignored with a linked issue and say so in the PR.

## Waiting for long jobs

Start long jobs in the background with an exit marker. Wait on the marker with a deadline, never on a fixed `sleep`, and read the job's status, not the wait's:

```sh
nohup sh -c './scripts/gate full; echo EXIT=$?' > gate.log 2>&1 &
timeout 5400 sh -c 'until grep -q "^EXIT=" gate.log; do sleep 15; done'; tail -n 20 gate.log; grep -qx 'EXIT=0' gate.log
```

## Reviews

- Review the frozen diff after the per-fix check passes, before the full check.
- Code review: at most 2 rounds. Nits never start a new round. A re-review covers only what changed.
- Council: an item passes at 9.5. Minor (S3) findings go to the next wave's backlog and do not block. At most 3 rounds per wave. Later rounds re-check open findings and changed items only.
- If something is still open after the round cap, stop and ask the owner.

## Threads

- One orchestrator thread per wave. Start it from a handoff file with the goal, standing approvals, candidate commit, open findings and backlog.
- The orchestrator never waits on a check itself. Child agents run checks and commit in their own worktrees.
