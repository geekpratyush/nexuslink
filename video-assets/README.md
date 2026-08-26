# Video assets

Everything needed to assemble a three-minute NexusLink video in Microsoft Clipchamp: six finished
MP4 clips, eleven title cards, twenty-one screenshots of the running application and five
transparent overlays. Drag the folder straight into Clipchamp's media panel and it will import.

Start with [`SCRIPT.md`](SCRIPT.md): it is the shot list, the timings and the narration, scene by
scene. This page is the inventory and the assembly notes.

---

## What is here

| Folder | What it is | Size |
|---|---|---|
| `motion/` | **Seven finished MP4 clips**, six of them genuinely animated | 1920×1080, 30fps |
| `slides/` | Eleven designed 1920×1080 title cards — the spine of the video | full-frame |
| `screenshots/` | Twenty-one real screens of the running application, dark theme, 1920×1012 | full-frame |
| `overlays/` | Transparent PNGs to lay **over** footage or screenshots | full-frame, alpha |
| `src/` | The sources: slide HTML, the renderer, the screenshot harness | — |

### Motion clips

| File | Length | What it is |
|---|---|---|
| `01-logo-reveal.mp4` | 5.4s | **Animated.** The mark draws itself — the red canopy strokes on, the N builds run by run, the nodes pop — then the wordmark rises and the tagline tracks in from wide letter-spacing. |
| `02-built-at-citi.mp4` | 5.4s | **Animated.** The lockup assembles, then "Built at Citi. Built for Citi." rises in. |
| `03-protocol-wall.mp4` | 6s | **Animated.** All 26 protocol chips build in, one every 85 ms. |
| `04-product-montage.mp4` | 28s | **The centrepiece.** Eight real screens — connected, with live data — each pushed in and cross-dissolved. Ready to use as one clip. |
| `05-credits.mp4` | 7s | **Animated.** The lockup fades up, then the program, the name and the tagline, each on its own beat. |
| `06-request-response.mp4` | 4.6s | A request in flight dissolving into the 200 OK and its payload — two real captures from one run. |
| `07-problem.mp4` | 6s | **Animated.** The headline and body rise in, then the three red stat cards land one after another. |

Rebuild them with:

```bash
python3 video-assets/src/make_animation.py     # the six animated scenes
./video-assets/src/make_motion.sh              # the montage and the request/response clip
```

**How the animation works**, in case you want to change it: each scene is ordinary CSS, written
*paused*, with every element's `animation-delay` driven by one variable, `--t`. Setting `--t: 1.4s`
renders the exact state the animation would be in 1.4 seconds in. The renderer steps `--t` frame by
frame, screenshots each one with headless Chrome, and hands the frames to ffmpeg. No screen capture,
no video editor, and the same `--t` always produces the same pixel — so a scene can be tweaked and
re-rendered identically. Scene definitions are in `src/make_animation.py`; each one also leaves a
readable HTML snapshot in `src/anim/`.

### Slides

| File | Use |
|---|---|
| `00-citi.png` | Built at Citi, built for Citi — with the logo slot |
| `10-credits.png` | End credits — the program and the author, with the logo slot |
| `01-title.png` | Opening card |
| `02-problem.png` | Six tools to test one transaction |
| `03-one-console.png` | The protocol wall — all 26 |
| `04-stack.png` | Layer-by-layer table (SQL, Kafka, Mongo, MQ, REST/GraphQL, S3) |
| `05-shared.png` | One vault, one environment, one history |
| `06-security.png` | Offline-first, no telemetry, your Artifactory |
| `07-cost.png` | The licence arithmetic — **fill in your own numbers first** |
| `08-start.png` | Three steps to running |
| `09-close.png` | Closing card |

### Screenshots

Twenty-one real captures of the running application, all 1920×1012, dark theme:

| | |
|---|---|
| `01-one-console` | **The hero shot** — eight protocol tabs open in one window (REST, SQL, Kafka, Mongo, IBM MQ, GraphQL, S3, Redis), four of them connected |
| `02-rest-client` | REST, with a live `200 OK` and a real JSON response |
| `03-sql-workbench` | **Connected** — schema tree, query, and rows in the result grid |
| `04-kafka` | **Connected** — five topics with partitions, produce/consume/lag/groups |
| `05-mongodb` | **Connected** — MongoDB 7.0, database tree, query bar |
| `06-ibmmq` | Queue manager, channel, browse and put |
| `07-graphql` | Query editor, variables, introspection, subscriptions |
| `08-s3` | Object storage explorer and commander |
| `09-redis` | **Connected** — Redis 7.4, keys listed, console and pub/sub |
| `10-sftp-commander` | Two-pane commander with the transfer queue |
| `11-grpc` | Service and method pickers, request JSON |
| `12-ldap` | Search bar, filter builder, DIT tree |
| `13-mqtt` | Subscribe, publish, live message list |
| `14-certificates` | Certificate manager — generate, import, build bundles |
| `15-environments` | Environment variables per environment |
| `16-secret-vaults` | HashiCorp Vault, AWS Secrets Manager, CyberArk |
| `17-metrics` | Live metrics dashboard with the throughput chart |
| `20-azure-blob` | Azure Blob explorer and commander |
| `21-snmp` | MIB browser, GET and WALK |
| `22-ssh-terminal` | Embedded SSH terminal |
| `23-rabbitmq` | Exchanges, queues, bindings, publish and consume |

They were taken against a throwaway profile, so they show a clean workbench — no saved connections,
no history, nothing from anyone's machine.

### Overlays

| File | Use |
|---|---|
| `logo-bug.png` | Small frosted logo plate, top right. Leave it on for the whole screenshot montage. |
| `lower-third-blank.png` | Frosted name plate, bottom left. Put your own text boxes over it — see SCRIPT.md. |
| `frosted-panel.png` | Large centred frosted card with the logo and tagline. **This is the "logo on frosted glass in the office" shot** — put it over stock office footage. |
| `frosted-strip.png` | Bottom gradient. Drop it under text that sits on busy footage so the words stay readable. |
| `cobrand-lockup.png` | **NexusLink + Citi co-branding**, frosted, centred — the "logo on glass in the office" beat. Put it over stock office footage. |

---

## The Citi logo

**Put the approved file here and everything picks it up:**

```
video-assets/src/citi-logo.svg      (or citi-logo.png)
```

Then re-render:

```bash
python3 video-assets/src/make_slides.py       # the two Citi slides + the co-brand overlay
python3 video-assets/src/make_animation.py citi credits    # the two animated clips
```

The logo appears in all five places at the right size, and the dashed placeholder disappears. No
editing in Clipchamp, no positioning by hand.

### Or drop it in with Clipchamp instead

Clipchamp can do it too — put the logo on a track **above** the clip and it covers the dashed box.
It is more fiddly than re-rendering, but it needs no tooling. The boxes, measured on the 1920×1080
frame:

| Asset | Box (px) | Centre | Size | Placeholder appears |
|---|---|---|---|---|
| `02-built-at-citi.mp4`, `slides/00-citi.png` | x 932, y 338, 300×130 | 56.4% across, 37.3% down | 15.6% × 12.0% | 1.3 s in (solid by 1.9 s) |
| `05-credits.mp4`, `slides/10-credits.png` | x 936, y 299, 250×104 | 55.3% across, 32.5% down | 13.0% × 9.6% | 1.2 s in (solid by 1.8 s) |
| `overlays/cobrand-lockup.png` | x 1198, y 480, 188×120 | 67.3% across, 50.0% down | 9.8% × 11.1% | static |

For the two MP4s: add the logo image on the track above, drag its left edge to about **1.3 seconds**
into the clip so it arrives with the placeholder rather than before it, then position and scale it to
the box. Give it a 0.3 s fade-in so it matches the way the slot fades up. Scale to fit *inside* the
box — the logo should not touch its edges.

Re-rendering is still the better route where you can: the placeholder disappears rather than being
covered, so there is no risk of a dashed edge peeking out at the wrong scale.

Until the file exists, those five assets draw a dashed slot of exactly the right footprint:
`slides/00-citi.png`, `slides/10-credits.png`, `overlays/cobrand-lockup.png`, and the
`02-built-at-citi` and `05-credits` clips.

**Why it is not already filled in.** Reproducing a company's trademark from memory gets the mark,
the proportions and the red subtly wrong, and that is exactly what a brand-conscious audience
notices. Co-branding is usually governed by brand rules too. Take the real asset from Citi's brand
portal — an SVG is best — and check the placement against whatever internal guidance applies.

## The office footage

There is no stock photography in this folder, and it should not be faked: Clipchamp has a stock
library built in, and using it is both better looking and properly licensed for internal use.

In Clipchamp, open **Content library → Videos** and search:

- `modern office glass` · `office glass wall meeting` · `developers working office`
- `team collaboration technology` · `data center corridor` · `city office night`

Pick two or three clips, mute them, slow them to 0.5×, and drop **`overlays/frosted-panel.png`** on
top for the opening or closing beat. That gives you the logo-on-frosted-glass-in-an-office look
without a single fabricated image. Keep the footage behind the panel dim — the panel is the subject.

---

## Assembling it in Clipchamp

1. **New project**, 16:9, 1080p.
2. Drag this whole folder into the media panel.
3. Lay the clips, slides and screenshots on **track 1** in the order in `SCRIPT.md` — its timeline
   table gives the start time and length of all fifteen beats. The MP4s already fade in and out, so
   butt them straight against their neighbours; only the stills need transitions.
4. Put the overlays on **track 2**, above the clips they belong to.
5. Add text boxes on **track 3** for the lower-third words.
6. Record the narration with Clipchamp's recorder, or use text-to-speech and paste the script in
   scene by scene. Keep it on its own audio track.
7. Add music underneath at about −18 dB (search terms are in `SCRIPT.md`).
8. Select every clip boundary and apply a **cross dissolve, 0.3–0.5s**.
9. Add a gentle **zoom** to each screenshot (Clipchamp's *Pan & zoom*, 100% → 104%) so no shot is
   static.
10. Export **1080p, 30fps**. Three minutes lands around 150–250 MB.

**A note on restraint.** The single biggest thing that separates a video that impresses management
from one that looks amateur is transitions. Use only cross dissolves. No spins, no bounces, no
sliding text. The design in these assets is doing the work already.

---

## Regenerating anything

The slides are HTML — edit and re-render, no design tool needed:

```bash
# edit video-assets/src/slides/07-cost.html (or make_slides.py for structural changes)
python3 video-assets/src/make_slides.py
```

Screenshots come from the application snapshotting its own scene:

```bash
./dist/publish.sh --local --host-only              # build the JAR once
./video-assets/src/shoot.sh 04-kafka kafka 10      # <name> <tab> <seconds-to-settle>
```

To capture with **live data**, start the test environment first and add the demo-connect hook:

```bash
cd test-env && docker compose up -d postgres mongo kafka redis rabbitmq
./video-assets/src/shoot.sh 04-kafka kafka 18 -Dnexuslink.democonnect=1
```

`-Dnexuslink.democonnect` connects every opened view that knows how, using the defaults already in
its connection bar. That is how the Kafka, MongoDB, Redis and SQL screens in this folder come to show
real topics, databases, keys and rows.

The harness runs the app against a throwaway `user.home`, so captures always show a clean profile
and can never include the operator's own connections or history. It reads the application's own
pixels — never the desktop — so a screen grab cannot pick up anything that happens to be on screen.

---

## Two things to decide before you present

**The cost slide is blank on purpose.** `07-cost.png` lists the categories NexusLink replaces with
empty seat and cost columns. Nobody here knows what your organisation pays for those licences, and
guessing would be the fastest way to lose the room. Fill in the real figures from your own contracts,
re-render, and let the arithmetic speak.

**Claims worth keeping factual.** Everything in the script is checkable: twenty-six protocols, one
vault, one environment set, one history, offline-first, distributed from your own Artifactory, Java
21, one file to install. That is a strong enough case on its own — resist the urge to add
superlatives about competing products in the voiceover, because that is the part someone in the room
will challenge.
