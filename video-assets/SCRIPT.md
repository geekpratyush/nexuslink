# NexusLink — 3-minute video script

Total run time **3:00**. Narration is written for a calm, unhurried read at roughly 145 words a
minute — about 420 words. Read it slower than feels natural; the pauses are where the screenshots
land.

Every asset named below is in this folder. Nothing here needs to be drawn, bought or invented.

---

## Scene 1 — Open (0:00 – 0:12)

| | |
|---|---|
| **Visual** | `slides/01-title.png`. Slow zoom in, 105% → 100%. |
| **On screen** | NexusLink · One Console · Every Protocol · Zero Context Switching |
| **Audio** | Music starts alone for the first three seconds, then ducks under the voice. |

> **Narration.** This is NexusLink. One desktop workbench for every protocol our systems actually
> run on — built in-house, for us.

---

## Scene 2 — The problem (0:12 – 0:34)

| | |
|---|---|
| **Visual** | `slides/02-problem.png`. Hold. Let the three red figures land. |
| **Transition in** | Cross dissolve, 0.4s |

> **Narration.** Think about how you test a single payment today. It leaves a REST endpoint. It
> lands on a Kafka topic. It updates a row in Oracle, drops a document in Mongo, triggers an IBM MQ
> message, and archives to S3. That is six different products, six logins, six sets of credentials —
> to follow one transaction. And none of those tools can see what the others just did.

---

## Scene 3 — The answer (0:34 – 0:46)

| | |
|---|---|
| **Visual** | `slides/03-one-console.png`. Optional: reveal the protocol chips left to right with a wipe. |

> **Narration.** NexusLink replaces all of it with one window. Twenty-six protocols, one set of
> habits, one place to look.

---

## Scene 4 — The product, in use (0:46 – 1:46)

Sixty seconds of real screens. **Six to nine seconds each**, cross-dissolve between them, with a
slow 3–4% zoom on each so nothing sits still. Drop `overlays/logo-bug.png` in the top-right for the
whole sequence, and put a lower third on each screen (see *Lower thirds* below).

| Time | Screenshot | Lower third |
|---|---|---|
| 0:46 | `screenshots/02-rest-client.png` | **REST & API testing** — collections, auth, assertions |
| 0:54 | `screenshots/03-sql-workbench.png` | **SQL workbench** — query, edit, explain, export |
| 1:02 | `screenshots/04-kafka.png` | **Kafka** — produce, consume, consumer lag, replay |
| 1:10 | `screenshots/05-mongodb.png` | **MongoDB** — find, aggregate, change streams |
| 1:18 | `screenshots/06-ibmmq.png` | **IBM MQ** — browse queues, put, replay the DLQ |
| 1:26 | `screenshots/07-graphql.png` | **GraphQL** — introspection, variables, subscriptions |
| 1:33 | `screenshots/08-s3.png` | **S3 & object storage** — browse, upload, presign |
| 1:40 | `screenshots/10-sftp-commander.png` | **File transfer** — two-pane commander, queue, compare |

> **Narration.** REST, with collections, enterprise authentication and assertions. A SQL workbench
> that speaks the dialect your engine actually accepts. Kafka — produce, consume, watch consumer lag,
> replay a topic. MongoDB, with aggregation and change streams. IBM MQ: browse the queue, put a
> message, replay the dead-letter queue. GraphQL and gRPC. S3 and object storage. SFTP, with a
> two-pane commander and a transfer queue. Every one of them, in the same window, with the same
> keystrokes.

---

## Scene 5 — Why one tool beats six (1:46 – 2:02)

| | |
|---|---|
| **Visual** | `slides/05-shared.png`. Hold. |

> **Narration.** And because it is one tool, the tedious parts happen once. One encrypted vault for
> credentials. One set of environment variables that resolve the same way in a URL, a broker or a
> bucket name. One searchable history of everything you have run — that you can replay.

---

## Scene 6 — Security posture (2:02 – 2:18)

| | |
|---|---|
| **Visual** | `slides/06-security.png`. Hold. |

> **Narration.** It is offline-first. No account, no telemetry, nothing phoning home. It runs on an
> air-gapped network, and it is distributed from the Artifactory we already control.

---

## Scene 7 — The commercial case (2:18 – 2:36)

| | |
|---|---|
| **Visual** | `slides/07-cost.png` — **fill in the figures before you present this.** |

> **Narration.** Then there is the licence line. Every one of those six tools is a per-seat renewal,
> multiplied by the size of engineering. NexusLink is ours: no per-seat licence, no renewal, no
> vendor review. Put our own contract numbers against that list and the saving speaks for itself.

**Before you export:** the seats and cost columns are deliberately blank. Open
`src/slides/07-cost.html`, replace the `&mdash;` cells with your real numbers, and re-run
`python3 video-assets/src/make_slides.py`. Do not present invented figures.

---

## Scene 8 — How easy it is to start (2:36 – 2:52)

| | |
|---|---|
| **Visual** | `slides/08-start.png`, then optionally cut to `screenshots/02-rest-client.png` on the last line. |

> **Narration.** Starting takes a minute. Download one file for your platform and run it. It reads
> the Artifactory settings already on your machine, downloads once, and opens. Every run after that
> starts from the local copy — instantly, even with no network. All you need is Java 21.

---

## Scene 9 — Close (2:52 – 3:00)

| | |
|---|---|
| **Visual** | `slides/09-close.png`. Slow zoom out. Music comes back up. |

> **Narration.** Stop switching windows. Start shipping.

---

## Lower thirds

`overlays/lower-third-blank.png` is a frosted-glass plate with the logo and two lines of placeholder
text. In Clipchamp, place it over the screenshot, then add two text boxes on top of it:

- **Title** — 44 px, semibold, white, positioned over `TITLE HERE`
- **Subtitle** — 24 px, `#93A6C4`, positioned over `subtitle here`

Build it once, then copy and paste it down the timeline and retype the words for each screen. Fade
each one in over 0.3s, 1 second after its screenshot appears.

---

## Music and pacing

- Pick something restrained from Clipchamp's stock library: **corporate / ambient / technology**,
  90–110 bpm, no vocals, no drop. Search terms: *corporate inspiring*, *ambient technology*,
  *minimal documentary*.
- Set the music to about **-18 dB** under the narration, and let it play alone for the first three
  seconds and the last five.
- Cut on the beat where you can, especially through the screenshot montage in Scene 4.
- Every transition should be a **cross dissolve of 0.3–0.5s**. Nothing spins, bounces or wipes in
  from off-screen — the restraint is what makes it look expensive.

---

## If you want a live-data version

The screenshots show the product with nothing connected — clean, but quiet. For screens with real
data in them (topics listed, rows returned, messages flowing), start the local test environment and
re-shoot:

```bash
cd test-env && docker compose up -d          # Kafka, Mongo, Postgres, MinIO, …
./video-assets/src/shoot.sh 04-kafka kafka 12
```

Then connect inside the app before the snapshot fires by raising the delay
(`./video-assets/src/shoot.sh 04-kafka kafka 30`) and clicking Connect yourself while it counts down.
