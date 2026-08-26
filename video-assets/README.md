# Video assets

Everything needed to assemble a three-minute NexusLink video in Microsoft Clipchamp. All of it is
PNG — drag the folder straight into Clipchamp's media panel and it will import.

Start with [`SCRIPT.md`](SCRIPT.md): it is the shot list, the timings and the narration, scene by
scene. This page is the inventory and the assembly notes.

---

## What is here

| Folder | What it is | Size |
|---|---|---|
| `slides/` | Nine designed 1920×1080 title cards — the spine of the video | full-frame |
| `screenshots/` | Thirteen real screens of the running application, dark theme, 1920×1012 | full-frame |
| `overlays/` | Transparent PNGs to lay **over** footage or screenshots | full-frame, alpha |
| `src/` | The sources: slide HTML, the renderer, the screenshot harness | — |

### Slides

| File | Use |
|---|---|
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
| `01-one-console` | **The hero shot** — eight protocol tabs open in one window: REST, SQL, Kafka, Mongo, IBM MQ, GraphQL, S3, Redis |
| `02-rest-client` | REST, with a live `200 OK` and a real JSON response |
| `03-sql-workbench` | SQL client — schema tree, editor, result grid |
| `04-kafka` | Produce, consume, consumer lag, groups, cluster, schema registry |
| `05-mongodb` | Query bar, operations, projection view |
| `06-ibmmq` | Queue manager, channel, browse and put |
| `07-graphql` | Query editor, variables, introspection, subscriptions |
| `08-s3` | Object storage explorer and commander |
| `09-redis` | Console and pub/sub |
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

---

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
3. Lay the slides and screenshots on **track 1** in the order given in `SCRIPT.md`, each at the
   duration in the timing column.
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
