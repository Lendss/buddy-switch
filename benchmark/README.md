# Mobile-use benchmark

How fast and how reliably different models operate a real Android phone through Buddy.

![Median time per task](carousel-1-overall.png)

## Setup

- Phone: OnePlus 12, Android 16 (OxygenOS 16.0.10), German locale.
- Buddy 2.1.14, each model started in a fresh chat with the same prompt. Default effort (no effort setting passed).
- The 5 test apps were set to **Trust**, so no approval cards could add waiting time.
- Before every run, the app is reset (Settings back to its main page, Calculator cleared, Chrome on a blank page, Maps and YouTube backed out to their start screen), then Home.
- 2 runs per model and task. Time is measured from sending the prompt until the agent's final answer.

## Tasks

| Task | Prompt |
|---|---|
| android | Open Settings and tell me which Android version this phone runs. |
| calc | Open the Calculator app, compute 1234 × 5678 there, and tell me the result. |
| weather | Open Chrome and find the current temperature in Berlin. Tell me the number. |
| maps | Open Google Maps and tell me how long the drive from Berlin Hauptbahnhof to the Brandenburg Gate takes right now. |
| youtube | Open YouTube, search for "lofi hip hop" and start playing the first video. |

## Results

Median time per task over all 10 runs:

| Model | Agent | Median | Correct | Median steps |
|---|---|---|---|---|
| Claude Sonnet 5.5 | Claude Code | 25 s | 10/10 | 12 |
| Claude Opus 5.5 | Claude Code | 27 s | 10/10 | 8 |
| GPT-6 Luna | Codex | 29 s | 7/10 | 11 |
| Claude Haiku 5.5 | Claude Code | 30 s | 10/10 | 14 |
| GPT-6.1 Sol | Codex | 36 s | 10/10 | 13 |
| Space Bunny (free) | OpenCode | 43 s | 10/10 | 15 |
| Big Pickle (free) | OpenCode | 112 s | 10/10 | 12 |

Answers were checked by hand. Luna's misses: twice it gave today's high and low instead of the current temperature, and once it couldn't get Calculator to compute and answered from its own math. For YouTube the agent's own report that the video plays was accepted.

Per-task charts: [Settings](carousel-2-android.png), [Calculator](carousel-3-calc.png), [Chrome](carousel-4-weather.png), [Maps](carousel-5-maps.png), [YouTube](carousel-6-youtube.png).

Raw runs: [results.jsonl](results.jsonl) (`ms` = time, `tools` = phone tool calls, `reply` = the agent's answer).

## Limits

One phone, one locale, 2 runs per model and task: a first look, not a definitive ranking. Free OpenCode models change often and some fail on and off (beam, for example, failed half its runs with a server error and isn't listed).
