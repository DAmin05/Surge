#!/usr/bin/env python3
"""Renders the headline results (docs/results/headline/*.json) as static SVG charts.

    scripts/charts.py            # writes docs/results/headline/*.svg

Charts follow one set of rules: one y-axis per chart, 2 px lines, recessive grid, a
legend plus direct end labels for two or more series, chaos windows as shaded bands,
<title> tooltips on marks, and a dark palette selected by prefers-color-scheme. Colors
are the validated categorical slots (CVD-safe in light and dark).
"""

from __future__ import annotations

import html
import json
import os

DIR = "docs/results/headline"
W, H = 720, 300
M = {"top": 58, "right": 175, "bottom": 40, "left": 60}

STYLE = """
<style>
  .bg { fill: #fcfcfb } .grid { stroke: #e1e0d9 } .axis { fill: #898781 }
  .ink { fill: #0b0b0b } .ink2 { fill: #52514e } .band { fill: rgba(208,59,59,.10) }
  .s1 { stroke: #2a78d6 } .s2 { stroke: #eb6834 } .s3 { stroke: #1baf7a } .s4 { stroke: #eda100 }
  .f1 { fill: #2a78d6 } .f2 { fill: #eb6834 } .f3 { fill: #1baf7a } .f4 { fill: #eda100 }
  text { font-family: system-ui, -apple-system, "Segoe UI", sans-serif }
  @media (prefers-color-scheme: dark) {
    .bg { fill: #1a1a19 } .grid { stroke: #2c2c2a } .axis { fill: #898781 }
    .ink { fill: #ffffff } .ink2 { fill: #c3c2b7 } .band { fill: rgba(208,59,59,.18) }
    .s1 { stroke: #3987e5 } .s2 { stroke: #d95926 } .s3 { stroke: #199e70 } .s4 { stroke: #c98500 }
    .f1 { fill: #3987e5 } .f2 { fill: #d95926 } .f3 { fill: #199e70 } .f4 { fill: #c98500 }
  }
</style>"""


def nice(v: float) -> float:
    if v <= 0:
        return 1
    import math

    p = 10 ** math.floor(math.log10(v))
    for m in (1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if v <= m * p:
            return m * p
    return 10 * p


def fmt(v: float) -> str:
    return f"{v:,.0f}" if v >= 10 else f"{v:.1f}"


def line_chart(path: str, title: str, unit: str, series: list[dict], bands: list[dict] = (),
               x_label: str = "seconds since the sale opened", target: dict | None = None) -> None:
    """series: [{label, points: [[x, y]...]}]; bands: [{x0, x1, label}]."""
    iw, ih = W - M["left"] - M["right"], H - M["top"] - M["bottom"]
    xmax = max((p[0] for s in series for p in s["points"]), default=1)
    ymax = nice(max([p[1] for s in series for p in s["points"]] + [target["value"] if target else 0]) * 1.08)
    x = lambda v: M["left"] + v / xmax * iw  # noqa: E731
    y = lambda v: M["top"] + ih - v / ymax * ih  # noqa: E731
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"'
           f' role="img" aria-label="{html.escape(title)}">', STYLE,
           f'<rect class="bg" width="{W}" height="{H}" rx="10"/>',
           f'<text class="ink" x="{M["left"]}" y="24" font-size="15" font-weight="600">{html.escape(title)}</text>']
    for b in bands:
        x0, x1 = x(max(0, b["x0"])), x(min(xmax, b["x1"]))
        out.append(f'<rect class="band" x="{x0:.1f}" y="{M["top"]}" width="{max(2, x1 - x0):.1f}" height="{ih}">'
                   f'<title>{html.escape(b["label"])}</title></rect>')
        out.append(f'<text class="ink2" x="{x0 + 4:.1f}" y="{M["top"] + 14}" font-size="11">'
                   f'{html.escape(b["label"])}</text>')
    for i in range(5):
        v = ymax * i / 4
        out.append(f'<line class="grid" x1="{M["left"]}" x2="{M["left"] + iw}" y1="{y(v):.1f}" y2="{y(v):.1f}"/>')
        out.append(f'<text class="axis" x="{M["left"] - 8}" y="{y(v) + 4:.1f}" font-size="11"'
                   f' text-anchor="end">{fmt(v)}</text>')
    xstep = nice(xmax / 6)
    v = 0.0
    while v <= xmax + 1e-9:
        out.append(f'<text class="axis" x="{x(v):.1f}" y="{H - 18}" font-size="11" text-anchor="middle">'
                   f'{v:.0f}</text>')
        v += xstep
    out.append(f'<text class="axis" x="{M["left"] + iw / 2}" y="{H - 4}" font-size="11"'
               f' text-anchor="middle">{x_label}</text>')
    out.append(f'<text class="axis" x="14" y="{M["top"] + ih / 2}" font-size="11" text-anchor="middle"'
               f' transform="rotate(-90 14 {M["top"] + ih / 2})">{html.escape(unit)}</text>')
    if target:
        ty = y(target["value"])
        out.append(f'<line class="grid" x1="{M["left"]}" x2="{M["left"] + iw}" y1="{ty:.1f}" y2="{ty:.1f}"'
                   f' stroke-dasharray="4 4" style="stroke:#898781"/>')
        out.append(f'<text class="ink2" x="{M["left"] + 6}" y="{ty - 6:.1f}" font-size="11">'
                   f'{html.escape(target["label"])}</text>')
    labels = []
    for i, s in enumerate(series, 1):
        pts = s["points"]
        if not pts:
            continue
        d = " ".join(f"{'M' if j == 0 else 'L'}{x(px):.1f},{y(py):.1f}" for j, (px, py) in enumerate(pts))
        out.append(f'<path class="s{i}" d="{d}" fill="none" stroke-width="2" stroke-linejoin="round"/>')
        # sparse hover targets with the exact value
        step = max(1, len(pts) // 40)
        for px, py in pts[::step]:
            out.append(f'<circle cx="{x(px):.1f}" cy="{y(py):.1f}" r="6" fill="transparent">'
                       f'<title>{html.escape(s["label"])}: {fmt(py)} {html.escape(unit)} at {px:.0f} s</title>'
                       f'</circle>')
        lx, ly = pts[-1]
        labels.append([y(ly), i, s.get("short", s["label"]), ly, x(lx)])
    labels.sort()
    for k in range(1, len(labels)):
        if labels[k][0] - labels[k - 1][0] < 14:
            labels[k][0] = labels[k - 1][0] + 14
    for ly, i, label, v, lx in labels:
        out.append(f'<circle class="f{i}" cx="{lx:.1f}" cy="{ly:.1f}" r="3"/>')
        out.append(f'<text class="ink2" x="{M["left"] + iw + 8}" y="{ly + 4:.1f}" font-size="11">'
                   f'{fmt(v)} {html.escape(label if len(series) > 1 else "")}</text>')
    if len(series) > 1:
        lx = M["left"]
        for i, s in enumerate(series, 1):
            out.append(f'<rect class="f{i}" x="{lx:.1f}" y="38" width="14" height="3" rx="1.5"/>')
            out.append(f'<text class="ink2" x="{lx + 18:.1f}" y="43" font-size="11">{html.escape(s["label"])}</text>')
            lx += 32 + 6.2 * len(s["label"])
    out.append("</svg>")
    with open(path, "w") as f:
        f.write("\n".join(out))


def bar_chart(path: str, title: str, unit: str, rows: list[tuple[str, float]]) -> None:
    """Horizontal bars, one series, sorted, every bar labeled with its value."""
    bar, gap = 18, 6
    h = M["top"] + len(rows) * (bar + gap) + 30
    left, iw = 170, W - 170 - 80
    step = nice(max(v for _, v in rows) * 1.05 / 4)
    vmax = step * 4
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {h}" width="{W}" height="{h}"'
           f' role="img" aria-label="{html.escape(title)}">', STYLE,
           f'<rect class="bg" width="{W}" height="{h}" rx="10"/>',
           f'<text class="ink" x="20" y="24" font-size="15" font-weight="600">{html.escape(title)}</text>']
    for i in range(5):
        v = vmax * i / 4
        gx = left + v / vmax * iw
        out.append(f'<line class="grid" x1="{gx:.1f}" x2="{gx:.1f}" y1="{M["top"] - 4}" y2="{h - 26}"/>')
        out.append(f'<text class="axis" x="{gx:.1f}" y="{h - 10}" font-size="11" text-anchor="middle">'
                   f'{fmt(v)}{html.escape(unit) if i == 4 else ""}</text>')
    for k, (label, v) in enumerate(rows):
        by = M["top"] + k * (bar + gap)
        bw = max(2, v / vmax * iw)
        out.append(f'<text class="ink2" x="{left - 8}" y="{by + 13}" font-size="12" text-anchor="end">'
                   f'{html.escape(label)}</text>')
        out.append(f'<rect class="f1" x="{left}" y="{by}" width="{bw:.1f}" height="{bar}" rx="4">'
                   f'<title>{html.escape(label)}: {v:.0f}{html.escape(unit)}</title></rect>')
        out.append(f'<text class="ink2" x="{left + bw + 6:.1f}" y="{by + 13}" font-size="11">{v:.0f}{unit}</text>')
    out.append("</svg>")
    with open(path, "w") as f:
        f.write("\n".join(out))


def load(name: str) -> dict | None:
    p = f"{DIR}/{name}.json"
    return json.load(open(p)) if os.path.exists(p) else None


def main() -> None:
    sell, before, chaos = load("sellout"), load("chaos-before"), load("chaos")
    runs = [r for r in (sell, before, chaos) if r]
    if not runs:
        raise SystemExit(f"no results in {DIR}")
    label = {"sellout": "no faults", "chaos-before": "primary killed, before fix",
             "chaos": "primary killed, after fix"}
    short = {"sellout": "no faults", "chaos-before": "before fix", "chaos": "after fix"}
    bands = []
    ref = chaos or before
    if ref and ref.get("chaos_window"):
        c0, c1, _ = ref["chaos_window"]
        bands = [{"x0": c0, "x1": c1, "label": "Redis primary down"}]

    line_chart(f"{DIR}/seats-sold.svg", "Seats sold, 10,000-seat event, 50 buyers/s", "seats",
               [{"label": label[r["name"]], "short": short[r["name"]], "points": r["series"]["seats_sold"]}
                for r in runs], bands,
               target={"value": 10000, "label": "capacity 10,000"})
    line_chart(f"{DIR}/checkouts.svg", "Confirmed checkouts per second", "checkouts/s",
               [{"label": label[r["name"]], "short": short[r["name"]], "points": r["series"]["checkouts_per_s"]}
                for r in runs], bands)
    for r in runs:
        b = bands if r["name"].startswith("chaos") else []
        line_chart(f"{DIR}/checkout-latency-{r['name']}.svg",
                   f"Checkout latency at the gateway ({label[r['name']]})", "ms",
                   [{"label": "p50", "points": r["series"]["checkout_p50_ms"]},
                    {"label": "p99", "points": r["series"]["checkout_p99_ms"]}], b)
    if sell and sell.get("cpu_avg_percent"):
        rows = sorted(sell["cpu_avg_percent"].items(), key=lambda kv: -kv[1])[:12]
        bar_chart(f"{DIR}/cpu.svg", "Average CPU during the sell-out (100% = one core)", "%", rows)
    print("charts written to", DIR)


if __name__ == "__main__":
    main()
