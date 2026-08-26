#!/usr/bin/env python3
"""
Renders genuinely animated clips — elements that draw, rise and build on screen — not pans over
stills.

How it works: every animation is written as ordinary CSS, but *paused*, with a negative
`animation-delay` driven by one variable, `--t`. Setting `--t: 1.4s` renders the exact state the
animation would be in 1.4 seconds in. Stepping `--t` frame by frame and screenshotting each one
gives frame-accurate output with no video capture involved, and it is deterministic — the same
`--t` always produces the same pixel.

    python3 video-assets/src/make_animation.py            # everything
    python3 video-assets/src/make_animation.py logo wall  # just these scenes

Needs headless Chrome and ffmpeg.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "video-assets" / "motion"
SRC = ROOT / "video-assets" / "src" / "anim"
FPS = 30

PROTOCOLS = [
    "REST", "GraphQL", "gRPC", "WebSocket", "SSE", "SQL / JDBC", "MongoDB", "Redis", "Kafka",
    "MQTT", "RabbitMQ", "JMS", "IBM MQ", "Solace", "SQS / SNS", "Pub/Sub", "Service Bus",
    "S3", "Azure Blob", "GCS", "SFTP", "FTP", "LDAP", "SNMP", "SSH", "MCP",
]
EMPHASIS = {"SQL / JDBC", "Kafka", "MongoDB", "IBM MQ", "REST", "GraphQL", "S3"}

# ---- the Citi logo -----------------------------------------------------------------------------
# Drop the approved asset at video-assets/src/citi-logo.svg (or .png) and every slide, overlay and
# animated clip picks it up on the next render. Until then a dashed placeholder slot is drawn, so
# nothing here ever ships an approximated trademark.
CITI = None
for _ext in ("svg", "png"):
    _p = ROOT / "video-assets" / "src" / f"citi-logo.{_ext}"
    if _p.exists():
        CITI = _p
        break


def citi_slot(width, height, label="Citi logo", note=None):
    """The official logo if it has been supplied, otherwise a placeholder of the same footprint."""
    if CITI is None:
        text = label if note is None else f"{label}<br>{note}"
        return (f'<div class="slot" style="width:{width}px;height:{height}px">{text}</div>')
    if CITI.suffix == ".svg":
        inner = CITI.read_text()
    else:
        import base64
        data = base64.b64encode(CITI.read_bytes()).decode()
        inner = f'<img src="data:image/png;base64,{data}" style="max-width:100%;max-height:100%">'
    return (f'<div style="width:{width}px;height:{height}px;display:flex;align-items:center;'
            f'justify-content:center">{inner}</div>')


# Shared look. Every animation below is paused and positioned in time by --t, so a keyframe set
# plus a per-element start offset is all a scene needs.
BASE = """
:root {
  --ink:#070B19; --fg:#F8FAFC; --muted:#93A6C4; --blue:#3B82F6; --red:#E31837; --line:#1E293B;
  --sans:'Segoe UI',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif;
}
* { box-sizing:border-box; margin:0; padding:0; }
html,body { width:1920px; height:1080px; overflow:hidden; }
body {
  background:var(--ink); color:var(--fg); font-family:var(--sans);
  display:flex; align-items:center; justify-content:center;
  background-image:
    radial-gradient(circle at 18% 22%, rgba(0,71,171,.38), transparent 46%),
    radial-gradient(circle at 84% 78%, rgba(227,24,55,.18), transparent 44%);
}
.grid {
  position:absolute; inset:0;
  background-image:
    linear-gradient(rgba(148,163,184,.075) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148,163,184,.075) 1px, transparent 1px);
  background-size:80px 80px;
  -webkit-mask-image:radial-gradient(ellipse at 50% 45%, #000 30%, transparent 74%);
}
.stage { position:relative; z-index:1; width:1520px; }

/* Every animated element uses this: paused, positioned by --t, holding its start and end states. */
.a { animation-play-state:paused; animation-fill-mode:both; animation-timing-function:cubic-bezier(.22,.9,.28,1); }

@keyframes rise   { from { opacity:0; transform:translateY(34px); } to { opacity:1; transform:none; } }
@keyframes pop    { from { opacity:0; transform:scale(.82); }      to { opacity:1; transform:none; } }
@keyframes fadein { from { opacity:0; }                            to { opacity:1; } }
@keyframes draw   { from { stroke-dashoffset:760; }                to { stroke-dashoffset:0; } }
@keyframes track  { from { opacity:0; letter-spacing:.9em; }       to { opacity:1; letter-spacing:.19em; } }
@keyframes wipe   { from { clip-path:inset(0 100% 0 0); }          to { clip-path:inset(0 0 0 0); } }
"""


def anim(name, dur, start):
    """CSS for one element: keyframes `name`, running `dur`, beginning at `start` seconds."""
    return f"animation-name:{name};animation-duration:{dur}s;animation-delay:calc({start}s - var(--t));"


def page(body, style=""):
    return (f'<!doctype html><meta charset="utf-8"><style>{BASE}{style}</style>'
            f'<body style="--t:{{T}}s">{body}</body>')


# ---- the mark, with each stroke drawable ------------------------------------------------------
def mark_svg(size, arc_at=0.0, n_at=0.45, dots_at=1.15):
    return f"""
<svg viewBox="0 0 512 512" width="{size}" height="{size}">
  <defs><linearGradient id="b" gradientUnits="userSpaceOnUse" x1="160" y1="250" x2="352" y2="396">
    <stop offset="0" stop-color="#3B82F6"/><stop offset="1" stop-color="#0047AB"/></linearGradient></defs>
  <rect width="512" height="512" rx="116" fill="#0A1128"/>
  <rect x="1.5" y="1.5" width="509" height="509" rx="114.5" fill="none" stroke="#1E293B" stroke-width="3"/>
  <path class="a" d="M 116 196 C 178 100 334 100 396 196" fill="none" stroke="#E31837"
        stroke-width="36" stroke-linecap="round"
        style="stroke-dasharray:400;{anim('draw', 0.9, arc_at)}"/>
  <g fill="none" stroke="url(#b)" stroke-width="42" stroke-linecap="round" stroke-linejoin="round">
    <path class="a" d="M 158 400 L 158 246" style="stroke-dasharray:200;{anim('draw', .45, n_at)}"/>
    <path class="a" d="M 158 246 L 354 400" style="stroke-dasharray:280;{anim('draw', .5, n_at + .28)}"/>
    <path class="a" d="M 354 400 L 354 246" style="stroke-dasharray:200;{anim('draw', .45, n_at + .62)}"/>
  </g>
  <circle class="a" cx="158" cy="246" r="23" fill="#93C5FD" style="{anim('pop', .35, dots_at)}"/>
  <circle class="a" cx="354" cy="400" r="23" fill="#93C5FD" style="{anim('pop', .35, dots_at + .12)}"/>
</svg>"""


def chips():
    out = []
    for i, p in enumerate(PROTOCOLS):
        hot = " hot" if p in EMPHASIS else ""
        out.append(f'<div class="chip{hot} a" style="{anim("pop", .5, 0.35 + i * 0.085)}">{p}</div>')
    return "".join(out)


SCENES = {
"logo": (5.4, page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center;margin-bottom:44px">{mark_svg(150)}</div>
  <h1 class="a" style="{anim('rise', .9, 1.5)}">Nexus<span style="color:var(--red)">Link</span></h1>
  <div class="tag a" style="{anim('track', 1.2, 2.1)}">One Console &middot; Every Protocol &middot; Zero Context Switching</div>
</div>""", """
h1 { font-size:118px; line-height:1.02; letter-spacing:-.045em; font-weight:600; }
.tag { font-size:26px; text-transform:uppercase; color:var(--muted); margin-top:26px; font-weight:500; }
""")),

"wall": (6.0, page(f"""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow a" style="{anim('rise', .7, 0)}">The alternative</div>
  <h2 class="a" style="{anim('rise', .8, .25)}">One console.<br>Twenty-six protocols.</h2>
  <div class="wall">{chips()}</div>
</div>""", """
.eyebrow { font-size:22px; letter-spacing:.42em; text-transform:uppercase; color:var(--blue);
           font-weight:600; margin-bottom:26px; }
h2 { font-size:82px; line-height:1.06; letter-spacing:-.035em; font-weight:600; }
.wall { display:flex; flex-wrap:wrap; gap:16px; margin-top:56px; }
.chip { border:1px solid var(--line); border-radius:14px; padding:16px 26px; font-size:30px;
        background:rgba(255,255,255,.03); }
.chip.hot { border-color:rgba(59,130,246,.65); background:rgba(59,130,246,.14); font-weight:600; }
""")),

"problem": (6.0, page(f"""
<div class="grid"></div>
<div class="stage">
  <div class="eyebrow a" style="{anim('rise', .7, 0)}">The working day today</div>
  <h2 class="a" style="{anim('rise', .8, .2)}">Six tools to test one<br>transaction.</h2>
  <div class="sub a" style="{anim('fadein', .9, .9)}">A payment leaves a REST endpoint, lands on a
  Kafka topic, updates a row in Oracle, drops a document in Mongo, triggers an IBM MQ message, and
  archives to S3. Six products. Six logins. One engineer, all day.</div>
  <div class="cols">
    <div class="card a" style="{anim('rise', .7, 1.9)}"><div class="k">6+</div><div class="t">tools open to follow one flow</div></div>
    <div class="card a" style="{anim('rise', .7, 2.2)}"><div class="k">4&times;</div><div class="t">credentials re-entered per environment</div></div>
    <div class="card a" style="{anim('rise', .7, 2.5)}"><div class="k">0</div><div class="t">shared history across any of them</div></div>
  </div>
</div>""", """
.eyebrow { font-size:22px; letter-spacing:.42em; text-transform:uppercase; color:var(--blue);
           font-weight:600; margin-bottom:26px; }
h2 { font-size:82px; line-height:1.06; letter-spacing:-.035em; font-weight:600; }
.sub { font-size:34px; color:var(--muted); margin-top:30px; line-height:1.5; max-width:1180px; }
.cols { display:flex; gap:26px; margin-top:60px; }
.card { flex:1; border:1px solid var(--line); border-radius:20px; padding:34px 32px;
        background:rgba(255,255,255,.032); }
.card .k { font-size:52px; font-weight:600; letter-spacing:-.03em; color:var(--red); }
.card .t { font-size:26px; color:var(--muted); margin-top:12px; line-height:1.4; }
""")),

"credits": (7.0, page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center;align-items:center;gap:48px;margin-bottom:52px">
    {mark_svg(104, arc_at=0.0, n_at=0.35, dots_at=0.95)}
    <div class="divider a" style="{anim('fadein', .6, 1.0)}"></div>
    <div class="a" style="{anim('fadein', .6, 1.2)}">{citi_slot(250, 104)}</div>
  </div>
  <div class="eyebrow a" style="{anim('rise', .7, 1.6)}">Developed as part of</div>
  <h2 class="a" style="{anim('rise', .8, 1.85)}">VPs &amp; SVPs who Code</h2>
  <div class="by a" style="{anim('fadein', .7, 2.7)}">By</div>
  <div class="name a" style="{anim('rise', .8, 2.9)}">Pratyush Ranjan Mishra</div>
  <div class="tag a" style="{anim('track', 1.1, 3.8)}">Nexus<span style="color:var(--red)">Link</span>
    &nbsp;&middot;&nbsp; One Console &middot; Every Protocol</div>
</div>""", """
.eyebrow { font-size:22px; letter-spacing:.42em; text-transform:uppercase; color:var(--blue);
           font-weight:600; margin-bottom:18px; }
h2 { font-size:64px; line-height:1.06; letter-spacing:-.03em; font-weight:600; }
.by { font-size:30px; color:var(--muted); margin-top:44px; }
.name { font-size:46px; font-weight:600; letter-spacing:-.02em; margin-top:6px; }
.tag { font-size:26px; text-transform:uppercase; color:var(--muted); margin-top:46px; font-weight:500; }
.divider { width:2px; height:104px; background:rgba(148,163,184,.35); }
.slot { width:250px; height:104px; border:2px dashed rgba(148,163,184,.55); border-radius:14px;
        display:flex; align-items:center; justify-content:center; color:var(--muted);
        font-size:22px; letter-spacing:.16em; text-transform:uppercase; }
""")),

"citi": (5.4, page(f"""
<div class="grid"></div>
<div class="stage" style="text-align:center">
  <div style="display:flex;justify-content:center;align-items:center;gap:56px;margin-bottom:56px">
    {mark_svg(130, arc_at=0.0, n_at=0.4, dots_at=1.0)}
    <div class="divider a" style="{anim('fadein', .6, 1.1)}"></div>
    <div class="a" style="{anim('fadein', .6, 1.3)}">{citi_slot(300, 130, note="drop the approved asset here")}</div>
  </div>
  <h2 class="a" style="{anim('rise', .9, 1.7)}">Built at Citi. Built for Citi.</h2>
  <div class="sub a" style="{anim('fadein', .9, 2.5)}">Not a licence to renew, not a vendor to review
  &mdash; an internal engineering tool, written here, running on our own network.</div>
</div>""", """
h2 { font-size:82px; line-height:1.06; letter-spacing:-.035em; font-weight:600; }
.sub { font-size:34px; color:var(--muted); margin:30px auto 0; line-height:1.5; max-width:1180px; }
.divider { width:2px; height:130px; background:rgba(148,163,184,.35); }
.slot { width:300px; height:130px; border:2px dashed rgba(148,163,184,.55); border-radius:14px;
        display:flex; align-items:center; justify-content:center; text-align:center; padding:0 20px;
        color:var(--muted); font-size:22px; letter-spacing:.16em; text-transform:uppercase; }
""")),
}

NAMES = {"logo": "01-logo-reveal", "citi": "02-built-at-citi", "wall": "03-protocol-wall",
         "credits": "05-credits", "problem": "07-problem"}


def render_scene(key):
    duration, template = SCENES[key]
    frames = int(duration * FPS)
    SRC.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp())
    try:
        # One HTML per frame, each pinned to its own instant by --t.
        for i in range(frames):
            t = i / FPS
            (tmp / f"f{i:04d}.html").write_text(template.replace("{T}", f"{t:.4f}"))
            subprocess.run(
                ["google-chrome", "--headless", "--disable-gpu", "--hide-scrollbars",
                 "--window-size=1920,1080", f"--screenshot={tmp}/f{i:04d}.png",
                 str(tmp / f"f{i:04d}.html")],
                capture_output=True, check=True)
        out = OUT / f"{NAMES[key]}.mp4"
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS),
             "-i", f"{tmp}/f%04d.png",
             "-vf", f"fade=t=out:st={duration - 0.5:.2f}:d=0.5,format=yuv420p",
             "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-r", str(FPS), str(out)],
            check=True)
        # Keep one source file so the animation can be inspected or tweaked by hand.
        (SRC / f"{key}.html").write_text(template.replace("{T}", "2.0"))
        print(f"  {out.relative_to(ROOT)}  ({duration}s, {frames} frames)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    wanted = sys.argv[1:] or list(SCENES)
    print("animated scenes:")
    for key in wanted:
        if key not in SCENES:
            print(f"  unknown scene: {key}")
            continue
        render_scene(key)


if __name__ == "__main__":
    sys.exit(main())
