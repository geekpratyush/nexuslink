#!/usr/bin/env python3
"""
Renders the video's title cards and overlays to PNG.

Every slide is written as a self-contained HTML page in video-assets/src/slides/ and screenshotted
with headless Chrome at 1920x1080 — edit the HTML and re-run to change a slide. Overlays are
rendered on a transparent background so they can sit on top of footage in Clipchamp.

    python3 video-assets/src/make_slides.py
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "video-assets" / "src" / "slides"
SLIDES = ROOT / "video-assets" / "slides"
OVERLAYS = ROOT / "video-assets" / "overlays"
MARK = (ROOT / "docs" / "assets" / "nexuslink-mark.svg").read_text()

# The brand, in one place.
CSS = """
:root {
  --ink:#070B19; --ink-2:#0A1128; --line:#1E293B;
  --fg:#F8FAFC; --muted:#93A6C4; --blue:#3B82F6; --deep:#0047AB; --red:#E31837;
  --sans:'Segoe UI',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif;
  --mono:'Cascadia Code',Consolas,'DejaVu Sans Mono',monospace;
}
* { box-sizing:border-box; margin:0; padding:0; }
html,body { width:1920px; height:1080px; }
body {
  background:var(--ink); color:var(--fg); font-family:var(--sans);
  display:flex; align-items:center; justify-content:center; overflow:hidden;
  background-image:
    radial-gradient(circle at 18% 22%, rgba(0,71,171,.38), transparent 46%),
    radial-gradient(circle at 84% 78%, rgba(227,24,55,.18), transparent 44%);
}
body.plain { background-image:none; }
body.transparent { background:transparent; background-image:none; }
.grid {
  position:absolute; inset:0; z-index:0;
  background-image:
    linear-gradient(rgba(148,163,184,.075) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148,163,184,.075) 1px, transparent 1px);
  background-size:80px 80px;
  -webkit-mask-image:radial-gradient(ellipse at 50% 45%, #000 30%, transparent 74%);
}
.stage { position:relative; z-index:1; width:1520px; }
.eyebrow {
  font-size:22px; letter-spacing:.42em; text-transform:uppercase; color:var(--blue);
  font-weight:600; margin-bottom:26px;
}
h1 { font-size:118px; line-height:1.02; letter-spacing:-.045em; font-weight:600; }
h1 .lk { color:var(--red); }
h2 { font-size:82px; line-height:1.06; letter-spacing:-.035em; font-weight:600; }
.sub { font-size:34px; color:var(--muted); margin-top:30px; line-height:1.5; max-width:1180px; }
.tag {
  font-size:26px; letter-spacing:.3em; text-transform:uppercase; color:var(--muted);
  margin-top:26px; font-weight:500;
}
.mark { width:150px; height:150px; margin-bottom:44px; }
.mark svg, .icon svg { width:100%; height:100%; display:block; }
.rule { width:96px; height:6px; background:var(--red); border-radius:3px; margin:40px 0; }
.cols { display:flex; gap:26px; margin-top:60px; }
.card {
  flex:1; border:1px solid var(--line); border-radius:20px; padding:34px 32px;
  background:rgba(255,255,255,.032);
}
.card .k { font-size:52px; font-weight:600; letter-spacing:-.03em; }
.card .k.red { color:var(--red); } .card .k.blue { color:var(--blue); }
.card .t { font-size:26px; color:var(--muted); margin-top:12px; line-height:1.4; }
.wall { display:flex; flex-wrap:wrap; gap:16px; margin-top:56px; max-width:1520px; }
.chip {
  border:1px solid var(--line); border-radius:14px; padding:16px 26px; font-size:30px;
  background:rgba(255,255,255,.03); letter-spacing:.01em;
}
.chip.hot { border-color:rgba(59,130,246,.65); background:rgba(59,130,246,.14); font-weight:600; }
.steps { margin-top:56px; display:flex; flex-direction:column; gap:30px; }
.step { display:flex; align-items:flex-start; gap:28px; }
.step .n {
  width:66px; height:66px; border-radius:50%; background:var(--blue); color:#061024;
  font-size:32px; font-weight:700; display:flex; align-items:center; justify-content:center; flex:none;
}
.step .b { font-size:38px; font-weight:600; }
.step .s { font-size:26px; color:var(--muted); margin-top:8px; }
.step code { font-family:var(--mono); font-size:30px; color:#DCE5F5;
  background:rgba(255,255,255,.06); padding:4px 14px; border-radius:8px; }
.rows { margin-top:52px; display:flex; flex-direction:column; gap:2px; }
.row { display:flex; align-items:center; gap:28px; padding:22px 4px; border-bottom:1px solid var(--line); }
.row .a { flex:1; font-size:34px; }
.row .b { width:300px; font-size:30px; color:var(--muted); text-align:right; }
.row .c { width:300px; font-size:30px; text-align:right; color:var(--blue); font-weight:600; }
.row.head { border-bottom-color:#334155; }
.row.head div { font-size:22px; letter-spacing:.2em; text-transform:uppercase; color:var(--muted); }
.fill { color:var(--red); font-family:var(--mono); }
.slot {
  border:2px dashed rgba(148,163,184,.55); border-radius:14px; display:flex; align-items:center;
  justify-content:center; color:#93A6C4; font-size:22px; letter-spacing:.16em; text-transform:uppercase;
  text-align:center; padding:0 20px;
}
.credit { font-size:30px; color:var(--muted); margin-top:10px; }
.credit b { color:var(--fg); font-weight:600; }
.divider { width:2px; background:rgba(148,163,184,.35); align-self:stretch; }
.foot { position:absolute; left:0; right:0; bottom:54px; text-align:center;
  font-size:22px; letter-spacing:.28em; text-transform:uppercase; color:#5C6B85; z-index:1; }
"""

MARK_DIV = f'<div class="mark">{MARK}</div>'


def page(body, cls="", extra=""):
    return (f'<!doctype html><meta charset="utf-8"><style>{CSS}{extra}</style>'
            f'<body class="{cls}">{body}</body>')


def chips(items, hot=()):
    return "".join(
        f'<div class="chip{" hot" if i in hot else ""}">{i}</div>' for i in items)


EMPHASIS = {"SQL / JDBC", "Kafka", "MongoDB", "IBM MQ", "REST", "GraphQL", "S3"}

PROTOCOLS = [
    "REST", "GraphQL", "gRPC", "WebSocket", "SSE", "SQL / JDBC", "MongoDB", "Redis", "Kafka",
    "MQTT", "RabbitMQ", "JMS", "IBM MQ", "Solace", "SQS / SNS", "Pub/Sub", "Service Bus",
    "S3", "Azure Blob", "GCS", "SFTP", "FTP", "LDAP", "SNMP", "SSH", "MCP",
]

SLIDES_HTML = {
"00-citi": page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center;align-items:center;gap:56px;margin-bottom:56px">
    <div class="icon" style="width:130px;height:130px">{MARK}</div>
    <div class="divider" style="height:130px"></div>
    <div class="slot" style="width:300px;height:130px">Citi logo<br>drop the approved asset here</div>
  </div>
  <h2>Built at Citi. Built for Citi.</h2>
  <div class="sub" style="margin:30px auto 0">Not a licence to renew, not a vendor to review &mdash;
  an internal engineering tool, written here, running on our own network.</div>
</div>"""),

"10-credits": page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center;align-items:center;gap:48px;margin-bottom:52px">
    <div class="icon" style="width:104px;height:104px">{MARK}</div>
    <div class="divider" style="height:104px"></div>
    <div class="slot" style="width:250px;height:104px">Citi logo</div>
  </div>
  <div class="eyebrow" style="margin-bottom:18px">Developed as part of</div>
  <h2 style="font-size:64px">VPs &amp; SVPs who Code</h2>
  <div class="credit" style="margin-top:44px">By</div>
  <div style="font-size:46px;font-weight:600;letter-spacing:-.02em;margin-top:6px">Pratyush Ranjan Mishra</div>
  <div class="tag" style="margin-top:46px">Nexus<span style="color:var(--red)">Link</span>
    &nbsp;&middot;&nbsp; One Console &middot; Every Protocol</div>
</div>"""),

"01-title": page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center">{MARK_DIV}</div>
  <h1>Nexus<span class="lk">Link</span></h1>
  <div class="tag">One Console &middot; Every Protocol &middot; Zero Context Switching</div>
</div>
<div class="foot">Internal engineering tooling</div>"""),

"02-problem": page("""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">The working day today</div>
  <h2>Six tools to test one<br>transaction.</h2>
  <div class="sub">A payment leaves a REST endpoint, lands on a Kafka topic, updates a row in
  Oracle, drops a document in Mongo, triggers an IBM MQ message, and archives to S3.
  Six products. Six logins. Six sets of credentials. One engineer, all day.</div>
  <div class="cols">
    <div class="card"><div class="k red">6+</div><div class="t">tools open to follow one flow</div></div>
    <div class="card"><div class="k red">4&times;</div><div class="t">credentials re-entered per environment</div></div>
    <div class="card"><div class="k red">0</div><div class="t">shared history across any of them</div></div>
  </div>
</div>"""),

"03-one-console": page(f"""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">The alternative</div>
  <h2>One console.<br>Twenty-six protocols.</h2>
  <div class="wall">{chips(PROTOCOLS, hot=EMPHASIS)}</div>
</div>"""),

"04-stack": page("""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">The stack you actually run</div>
  <h2>Every hop in one window.</h2>
  <div class="rows">
    <div class="row head"><div class="a">Layer</div><div class="b">What you do</div><div class="c">In NexusLink</div></div>
    <div class="row"><div class="a">SQL &amp; JDBC</div><div class="b">query, edit, explain</div><div class="c">SQL Client</div></div>
    <div class="row"><div class="a">Kafka</div><div class="b">produce, consume, replay, lag</div><div class="c">Kafka Client</div></div>
    <div class="row"><div class="a">MongoDB</div><div class="b">find, aggregate, change streams</div><div class="c">Mongo Client</div></div>
    <div class="row"><div class="a">IBM MQ &amp; JMS</div><div class="b">browse, put, DLQ replay</div><div class="c">MQ Client</div></div>
    <div class="row"><div class="a">REST &amp; GraphQL</div><div class="b">collections, auth, assertions</div><div class="c">API Client</div></div>
    <div class="row"><div class="a">S3 &amp; object storage</div><div class="b">browse, upload, presign</div><div class="c">Commander</div></div>
  </div>
</div>"""),

"05-shared": page("""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">Why one tool beats six</div>
  <h2>The parts nobody<br>wants to repeat.</h2>
  <div class="cols">
    <div class="card"><div class="k blue">One vault</div><div class="t">Credentials encrypted once, never shown in the clear, reused by every protocol.</div></div>
    <div class="card"><div class="k blue">One environment</div><div class="t">${VAR} resolves the same in a URL, a broker, a connection string or a bucket name.</div></div>
    <div class="card"><div class="k blue">One history</div><div class="t">Every call, query and message in a single searchable timeline you can replay.</div></div>
  </div>
  <div class="sub" style="margin-top:52px">Learn the workbench once. It applies to all twenty-six.</div>
</div>"""),

"06-security": page("""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">Built for a bank's network</div>
  <h2>Offline-first.<br>No account. No telemetry.</h2>
  <div class="cols">
    <div class="card"><div class="k">Air-gapped</div><div class="t">Runs with no internet at all. Nothing phones home, ever.</div></div>
    <div class="card"><div class="k">Your Artifactory</div><div class="t">Distributed from the repository you already control &mdash; no external download.</div></div>
    <div class="card"><div class="k">Guard rails</div><div class="t">Destructive statements previewed and confirmed; bulk writes counted before they run.</div></div>
  </div>
</div>"""),

"07-cost": page("""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">The commercial case</div>
  <h2>Do the arithmetic<br>with our numbers.</h2>
  <div class="rows">
    <div class="row head"><div class="a">Replaced by NexusLink</div><div class="b">Seats</div><div class="c">Annual cost</div></div>
    <div class="row"><div class="a">API client licences</div><div class="b fill">&mdash;</div><div class="c fill">&mdash;</div></div>
    <div class="row"><div class="a">Database IDE licences</div><div class="b fill">&mdash;</div><div class="c fill">&mdash;</div></div>
    <div class="row"><div class="a">Kafka / MQ UI licences</div><div class="b fill">&mdash;</div><div class="c fill">&mdash;</div></div>
    <div class="row"><div class="a">File transfer &amp; storage clients</div><div class="b fill">&mdash;</div><div class="c fill">&mdash;</div></div>
  </div>
  <div class="sub" style="margin-top:44px">NexusLink is built in-house: no per-seat licence, no renewal,
  no vendor review. Fill the column from our own contracts &mdash; that figure is the saving.</div>
</div>"""),

"08-start": page("""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow">Getting started</div>
  <h2>Running in under a minute.</h2>
  <div class="steps">
    <div class="step"><div class="n">1</div><div><div class="b">Download one file</div>
      <div class="s">nexuslink.sh on Mac and Linux, nexuslink.bat on Windows. That is the whole install.</div></div></div>
    <div class="step"><div class="n">2</div><div><div class="b">Run it</div>
      <div class="s"><code>./nexuslink.sh</code> &mdash; it reads the Artifactory settings you already have,
      downloads once, and starts.</div></div></div>
    <div class="step"><div class="n">3</div><div><div class="b">Every run after that is instant</div>
      <div class="s">Cached locally. Starts offline. Updates when you ask it to, not when it feels like it.</div></div></div>
  </div>
  <div class="sub" style="margin-top:48px">Requires Java 21. Nothing else to install, nothing to configure.</div>
</div>"""),

"09-close": page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center">{MARK_DIV}</div>
  <h2>Stop switching windows.<br>Start shipping.</h2>
  <div class="sub" style="margin:34px auto 0">Ask your lead for the launcher, or find NexusLink on the
  internal engineering portal.</div>
  <div class="tag" style="margin-top:40px">One Console &middot; Every Protocol &middot; Zero Context Switching</div>
</div>"""),
}

# Overlays: transparent PNGs to lay over footage in Clipchamp.
OVERLAY_HTML = {
"cobrand-lockup": page(f"""
<div style="position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);
            display:flex;align-items:center;gap:52px;
            background:rgba(10,17,40,.58);backdrop-filter:blur(24px);
            border:1px solid rgba(148,163,184,.30);border-radius:28px;padding:52px 72px;
            box-shadow:0 40px 120px rgba(0,0,0,.55)">
  <div class="icon" style="width:120px;height:120px;flex:none">{MARK}</div>
  <div>
    <div style="font-size:70px;font-weight:600;letter-spacing:-.03em">Nexus<span style="color:#E31837">Link</span></div>
    <div style="font-size:22px;letter-spacing:.26em;text-transform:uppercase;color:#93A6C4;margin-top:8px">
      Built at Citi &middot; Built for Citi</div>
  </div>
  <div class="divider" style="height:120px"></div>
  <div class="slot" style="width:240px;height:120px;flex:none">Citi logo</div>
</div>""", cls="transparent"),

"lower-third-blank": page("""
<div style="position:absolute;left:120px;bottom:150px;display:flex;align-items:center;gap:26px;
            background:rgba(7,11,25,.72);backdrop-filter:blur(18px);
            border:1px solid rgba(148,163,184,.28);border-left:6px solid #E31837;
            border-radius:18px;padding:26px 44px 26px 34px">
  <div class="icon" style="width:64px;height:64px;flex:none">%s</div>
  <div>
    <div style="font-size:44px;font-weight:600;letter-spacing:-.02em">TITLE HERE</div>
    <div style="font-size:24px;color:#93A6C4;margin-top:4px">subtitle here</div>
  </div>
</div>""" % MARK, cls="transparent"),

"logo-bug": page(f"""
<div style="position:absolute;right:90px;top:70px;display:flex;align-items:center;gap:18px;
            background:rgba(7,11,25,.6);backdrop-filter:blur(14px);
            border:1px solid rgba(148,163,184,.25);border-radius:16px;padding:16px 28px">
  <div class="icon" style="width:44px;height:44px;flex:none">{MARK}</div>
  <div style="font-size:30px;font-weight:600;letter-spacing:-.02em">NexusLink</div>
</div>""", cls="transparent"),

"frosted-panel": page(f"""
<div style="position:absolute;inset:150px 220px;border-radius:34px;
            background:rgba(10,17,40,.55);backdrop-filter:blur(26px);
            border:1px solid rgba(148,163,184,.30);
            box-shadow:0 40px 120px rgba(0,0,0,.55);
            display:flex;flex-direction:column;align-items:center;justify-content:center;gap:34px">
  <div class="icon" style="width:170px;height:170px">{MARK}</div>
  <div style="font-size:96px;font-weight:600;letter-spacing:-.04em">Nexus<span style="color:#E31837">Link</span></div>
  <div style="font-size:26px;letter-spacing:.3em;text-transform:uppercase;color:#93A6C4">
    One Console &middot; Every Protocol &middot; Zero Context Switching</div>
</div>""", cls="transparent"),

"frosted-strip": page("""
<div style="position:absolute;left:0;right:0;bottom:0;height:280px;
            background:linear-gradient(to top, rgba(7,11,25,.92), rgba(7,11,25,0));"></div>""",
     cls="transparent"),
}


def render(name, html, out_dir, transparent=False):
    SRC.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    src = SRC / f"{name}.html"
    src.write_text(html)
    cmd = ["google-chrome", "--headless", "--disable-gpu", "--hide-scrollbars",
           "--virtual-time-budget=2500", "--window-size=1920,1080",
           f"--screenshot={out_dir / (name + '.png')}"]
    if transparent:
        cmd.append("--default-background-color=00000000")
    cmd.append(str(src))
    subprocess.run(cmd, capture_output=True, check=True)
    print(f"  {out_dir.name}/{name}.png")


def main():
    print("slides:")
    for name, html in SLIDES_HTML.items():
        render(name, html, SLIDES)
    print("overlays:")
    for name, html in OVERLAY_HTML.items():
        render(name, html, OVERLAYS, transparent=True)


if __name__ == "__main__":
    sys.exit(main())
