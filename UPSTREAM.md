# Bringing over upstream Buddy versions

How Claude updates Buddy SW from [upstream Buddy](https://github.com/ghorbelhamdi/buddy-android). Buddy SW starts this
from Settings › Check for updates › Update: it opens a chat with a `[BUDDY_UPDATE]` message naming the new upstream
versions. That chat runs Claude Code in Termux with full tools, in the home folder; the repo is `~/buddy-switch`
(remotes: `origin` = Lendss/buddy-switch, `upstream` = ghorbelhamdi/buddy-android). See `SWITCH.md` for what this fork
is and how it differs.

## Versions

- `BuildInfo.UPSTREAM`: the upstream release this build is based on, e.g. `2.2.7`.
- `BuildInfo.VERSION`: `<UPSTREAM>-sw.<N>`, e.g. `2.2.7-sw.1`. N counts this fork's builds and never goes back (an
  update to 2.2.8 after `2.2.7-sw.4` gives `2.2.8-sw.5`).
- `BuildInfo.VERSION_CODE`: +1 for every build that is installed.

## What this fork always leaves out

Upstream features are taken unless they belong to these, or need them:

- **Agents other than Claude Code**: Codex, OpenCode, Antigravity/Gemini and any new one (their install, sign-in,
  models, dictation or voice, their branches in `buddy-run`). Chats keep Codex/OpenCode only as names.
- **The built-in Linux**: Alpine, proot (`jniLibs`, `native/`), `Linux.java`, the in-app terminal, the SSH server,
  the mic bridge, `codex-remote`. Here the agent is the Claude Code in Termux, run through `RUN_COMMAND`
  (`Termux.java`, `Helper.run`).
- **Upstream's identity and releases**: its application id, update repo (`Updates.java` follows upstream's
  releases on purpose, but installs nothing itself), Play-only parts.
- **Benchmarks** (`benchmark/`): upstream's own measurements; harmless to merge, nothing to review.

Things upstream changes in files this fork rewrote (`buddy-run`, `Account`, `SetupActivity`, `SignInActivity`,
`Bubble`, `ChatHub`, the strings) are ported by hand: keep this fork's behaviour (Termux paths, port 8775, one agent,
Remote per chat, the scheduler, Joy-Con, the fixes for this device's 4-second stalls) and add upstream's change on top.

## 1. Review (the first message): report, change nothing

```sh
cd ~/buddy-switch && git fetch upstream --tags
git log --oneline v<UPSTREAM>..v<latest>                      # every commit since our base
gh release view v<X> -R ghorbelhamdi/buddy-android            # the notes of each version in between
git diff --stat v<UPSTREAM> v<latest>; git diff v<UPSTREAM> v<latest> -- <files>
```

Go through **every** version after `BuildInfo.UPSTREAM`, not only the latest. Then answer in the chat, in the user's
language, in Markdown:

- per version, what's new for the user, in a few lines;
- a table: change · take / adapt / skip · why (adapt = it touches a file this fork rewrote, say how it would be
  ported);
- conflicts or risks you expect.

Stop there and wait: the user says what to take or skip, asks questions, then tells you to go ahead.

## 2. Bring it over (after the user's go-ahead)

1. Branch from `main`: `git switch -c update/<latest>` (the working tree must be clean; if not, ask).
2. `git merge v<latest>` and resolve: a file this fork deleted stays deleted unless a taken feature needs it; skipped
   features' code goes; ported changes keep this fork's behaviour (see above). If a merge is too tangled, cherry-pick
   or port by hand instead, and say so.
3. Set `BuildInfo`: `UPSTREAM = "<latest>"`, `VERSION = "<latest>-sw.<N+1>"`, `VERSION_CODE + 1`.
4. Build: `cd ~/buddy-switch && env -u LD_PRELOAD bash ./build.sh` (about a minute). Fix what fails.
5. Commit on the branch, as `Lendss <155894933+Lendss@users.noreply.github.com>`, one commit per logical change
   where it helps, saying what was taken from which upstream version and what was left out.
6. Report what's done and ask before installing: installing restarts Buddy, and this chat with it.

## 3. Install (when the user says yes)

Write your last message first (what's installed, what to check), then start the install in the background so the
run can end before Buddy restarts:

```sh
(sleep 5; su -c 'cp ~/buddy-switch/build/buddy-release.apk /data/local/tmp/b.apk && pm install -r /data/local/tmp/b.apk; rm -f /data/local/tmp/b.apk') >/dev/null 2>&1 &
```

The data, chats and settings stay. If the user later reports a problem, fix it on the branch and install again.

## 4. Finish (when the user says it works)

`git switch main && git merge --ff-only update/<latest> && git branch -d update/<latest>`, update the Progress
section of `SWITCH.md` if the fork's features changed, and push only when the user says so
(`git push origin main`).

Never touch the signing key (`~/.buddy-sw-keys/`), and never push without the user's word.
