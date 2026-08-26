# NexusLink — 3-minute video script

Total run time **3:00**. Narration reads at roughly 145 words a minute — about 400 words. Read it
slower than feels natural; the pauses are where the product lands.

Seven of the seventeen beats are **finished MP4 clips** in `motion/`, six of them genuinely
animated — elements draw, rise and build on screen. Drop them on the timeline as they are. The rest
are stills with a Clipchamp pan-and-zoom.

**Before you start:** drop your approved Citi logo into the three placeholder slots (see
[README](README.md#the-citi-logo)), and fill in the figures on the cost slide.

---

## The timeline

Seventeen beats, adding to exactly 3:00. **Six of them are animated clips** — the mark draws itself,
the protocol wall builds chip by chip, the stat cards rise in sequence. Where an animated clip is
followed by its own still, the cut is invisible: the clip's last frame *is* that still, so it reads
as "animate in, then hold" while the narration finishes.

| # | Start | Length | Asset | Kind |
|---|---|---|---|---|
| 1 | 0:00.0 | 5.4s | `motion/01-logo-reveal.mp4` | **animated** |
| 2 | 0:05.4 | 8.6s | office footage + `overlays/cobrand-lockup.png` | stock + overlay |
| 3 | 0:14.0 | 6.0s | `motion/07-problem.mp4` | **animated** |
| 4 | 0:20.0 | 10.0s | `slides/02-problem.png` (hold) | still |
| 5 | 0:30.0 | 6.0s | `motion/03-protocol-wall.mp4` | **animated** |
| 6 | 0:36.0 | 27.8s | `motion/04-product-montage.mp4` | clip |
| 7 | 1:03.8 | 4.6s | `motion/06-request-response.mp4` | clip |
| 8 | 1:08.4 | 12.6s | `slides/05-shared.png` | still |
| 9 | 1:21.0 | 12.0s | `slides/06-security.png` | still |
| 10 | 1:33.0 | 11.0s | `slides/04-stack.png` | still |
| 11 | 1:44.0 | 16.0s | `slides/07-cost.png` | still |
| 12 | 2:00.0 | 16.0s | `slides/08-start.png` | still |
| 13 | 2:16.0 | 10.0s | office footage + `overlays/frosted-strip.png` | stock + overlay |
| 14 | 2:26.0 | 13.0s | `slides/09-close.png` | still |
| 15 | 2:39.0 | 7.0s | `motion/05-credits.mp4` | **animated** |
| 16 | 2:46.0 | 5.4s | `motion/02-built-at-citi.mp4` | **animated** |
| 17 | 2:51.4 | 8.6s | `slides/00-citi.png` (hold) | still |

---

## Scene 1 — Open (0:00 – 0:05.4) · `motion/01-logo-reveal.mp4`

The mark draws itself: the red canopy strokes on, the N builds run by run, the two nodes pop, then
the wordmark rises and the tagline tracks in from wide letter-spacing.

> **Narration.** This is NexusLink. One desktop workbench for every protocol our systems actually
> run on.

---

## Scene 2 — Built here (0:05.4 – 0:14) · office footage + `overlays/cobrand-lockup.png`

Stock office footage, muted, slowed to 0.5×, with the co-branding lockup floating over it. This is
the "logo on frosted glass in the office" beat. Search terms are in the README.

> **Narration.** Built at Citi, for Citi — by our own engineers, on our own network.

---

## Scene 3 — The problem (0:14 – 0:30) · `motion/07-problem.mp4`, then `slides/02-problem.png`

The headline and body rise in, then the three red stat cards land one after another. Run the clip
(6s), then hold on the matching still (10s) while the narration finishes — the join is invisible.

> **Narration.** Think about how you test a single payment today. It leaves a REST endpoint. It
> lands on a Kafka topic. It updates a row in Oracle, drops a document in Mongo, triggers an IBM MQ
> message, and archives to S3. Six products. Six logins. Six sets of credentials — to follow one
> transaction. And none of them can see what the others just did.

---

## Scene 4 — The answer (0:30 – 0:36) · `motion/03-protocol-wall.mp4`

All twenty-six protocol chips build in, one every 85 milliseconds, with the seven emphasised ones
picked out in blue.

> **Narration.** NexusLink replaces all of it with one window. Twenty-six protocols, one set of
> habits.

---

## Scene 5 — The product (0:36 – 1:03.8) · `motion/04-product-montage.mp4`

Twenty-eight seconds of the real thing, already cut: the workbench with eight protocol tabs open,
then REST, SQL, Kafka, MongoDB, IBM MQ, GraphQL and S3, each with a slow push-in and a
cross-dissolve between them. **Connected, with live data** — real topics, real rows, real
databases.

Put `overlays/logo-bug.png` on a track above this clip for its whole length, and add a lower third
per screen (see *Lower thirds*). The screens change every 3.4 seconds in this order:

| Screen | Lower third |
|---|---|
| Eight tabs open | *(none — let the tab bar speak)* |
| REST | **REST & API testing** — collections, auth, assertions |
| SQL | **SQL workbench** — query, edit, explain, export |
| Kafka | **Kafka** — topics, partitions, consumer lag, replay |
| MongoDB | **MongoDB** — find, aggregate, change streams |
| IBM MQ | **IBM MQ** — browse queues, put, replay the DLQ |
| GraphQL | **GraphQL** — introspection, variables, subscriptions |
| S3 | **S3 & object storage** — browse, upload, presign |

> **Narration.** REST, with collections, enterprise authentication and assertions. A SQL workbench
> that speaks the dialect your engine actually accepts. Kafka — topics, partitions, consumer lag,
> replay. MongoDB, with aggregation and change streams. IBM MQ: browse the queue, put a message,
> replay the dead-letter queue. GraphQL. S3 and object storage. Every one of them in the same
> window, with the same keystrokes.

---

## Scene 6 — A request, live (1:03.8 – 1:08.4) · `motion/06-request-response.mp4`

The request in flight, then the response arriving — two real captures from one run, dissolved so
the two-hundred lands on screen.

> **Narration.** Send a request; the response, the timing and the payload come back in one place.

---

## Scene 7 — Why one tool beats six (1:08.4 – 1:21) · `slides/05-shared.png`

> **Narration.** Because it is one tool, the tedious parts happen once. One encrypted vault. One
> set of environment variables that resolve the same in a URL, a broker or a bucket name. One
> searchable history of everything you have run.

---

## Scene 8 — Security posture (1:21 – 1:33) · `slides/06-security.png`

> **Narration.** It is offline-first. No account, no telemetry, nothing phoning home. It runs on an
> air-gapped network, and it is distributed from the Artifactory we already control.

---

## Scene 9 — The stack (1:33 – 1:44) · `slides/04-stack.png`

> **Narration.** Every hop of a transaction — database, broker, queue, API, object store — in one
> window.

---

## Scene 10 — The commercial case (1:44 – 2:00) · `slides/07-cost.png`

**Fill in the figures before you present this.**

> **Narration.** Then there is the licence line. Every one of those tools is a per-seat renewal,
> multiplied by the size of engineering. NexusLink is ours: no per-seat licence, no renewal, no
> vendor review. Put our own contract numbers against that list and the saving speaks for itself.

Open `src/slides/07-cost.html`, replace the `&mdash;` cells with real numbers, and re-run
`python3 video-assets/src/make_slides.py`. Do not present invented figures.

---

## Scene 11 — How easy it is to start (2:00 – 2:16) · `slides/08-start.png`

> **Narration.** Starting takes a minute. Download one file for your platform and run it. It reads
> the Artifactory settings already on your machine, downloads once, and opens. Every run after that
> starts from the local copy — instantly, even with no network. All you need is Java 21.

---

## Scene 12 — People (2:16 – 2:26) · office footage + `overlays/frosted-strip.png`

Developers at desks, collaborating — stock footage, muted, slowed slightly, with the gradient strip
across the bottom so the closing line reads cleanly over it.

> **Narration.** One console. Every protocol. However you work.

---

## Scene 13 — Close (2:26 – 2:39) · `slides/09-close.png`

> **Narration.** Stop switching windows. Start shipping.

---

## Scene 14 — Credits (2:39 – 2:46) · `motion/05-credits.mp4`

The lockup fades up, then the program, then the name, then the tagline — each on its own beat.

> **Narration.** Developed as part of the VPs and SVPs who Code program, by Pratyush Ranjan Mishra.

---

## Scene 15 — Sign-off (2:46 – 3:00) · `motion/02-built-at-citi.mp4`, then `slides/00-citi.png`

No narration. The clip animates in (5.4s), then hold on the still (8.6s) as the music comes up and
fades out.

---

## Lower thirds

`overlays/lower-third-blank.png` is a frosted plate with the logo and two lines of placeholder text.
Place it over the montage, then add two text boxes on top:

- **Title** — 44 px, semibold, white, over `TITLE HERE`
- **Subtitle** — 24 px, `#93A6C4`, over `subtitle here`

Build it once, copy it down the timeline, retype the words. Fade each in over 0.3s, a second after
its screen appears.

---

## Music and pacing

- Clipchamp stock library: **corporate / ambient / technology**, 90–110 bpm, no vocals, no drop.
  Search *corporate inspiring*, *ambient technology*, *minimal documentary*.
- Music at about **−18 dB** under the narration. Let it play alone for the first three seconds and
  the last eight, under the credits.
- Every cut between stills: **cross dissolve, 0.3–0.5s**. The clips already fade in and out, so butt
  them straight against their neighbours.
- Nothing spins, bounces or slides. The restraint is what makes it read as an organisation video
  rather than a slideshow.
