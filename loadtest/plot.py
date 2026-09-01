"""실험 결과 CSV를 그래프로 그린다.

run-experiment.ps1 이 남긴 results.csv 를 읽어서, 스윕한 파라미터를 x축으로
처리량 / 응답 시간 / 초과 예약을 그린다.

중간값은 선으로, 개별 회차는 옅은 점으로 함께 찍는다. 점들이 벌어져 있으면
그 구간의 측정은 신뢰 구간이 넓다는 뜻이고, 선 위의 작은 차이는 결론으로
쓰면 안 된다. 편차를 지우지 않고 같이 보여주는 것이 목적이다.

실행:
    python loadtest/plot.py --label 경합강도
    python loadtest/plot.py --label 풀크기 --out loadtest/results/pool.png
"""

import argparse
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

# ── 색 (dataviz 레퍼런스 팔레트) ─────────────────────────────────────────
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"
GRID = "#e3e2df"

THROUGHPUT = "#2a78d6"          # 카테고리 슬롯 1, 단일 계열
# 백분위는 같은 값의 순서 있는 크기라 한 색상의 명도 단계로 표현한다.
LATENCY = {"p50_ms": "#86b6ef", "p95_ms": "#2a78d6", "p99_ms": "#104281"}
CRITICAL = "#d03b3b"            # 상태 색: 0이 아니면 결함
RUN_DOT = "#9a998f"

plt.rcParams.update({
    "font.family": ["Malgun Gothic", "DejaVu Sans"],
    "axes.unicode_minus": False,
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "axes.edgecolor": GRID,
    "axes.labelcolor": INK_MUTED,
    "text.color": INK,
    "xtick.color": INK_MUTED,
    "ytick.color": INK_MUTED,
    "grid.color": GRID,
    "font.size": 10,
})


def style_axis(ax, ylabel):
    ax.set_ylabel(ylabel, fontsize=9)
    ax.grid(True, axis="y", linewidth=0.8, alpha=0.9)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.spines["left"].set_color(GRID)
    ax.spines["bottom"].set_color(GRID)


def label_end(ax, x, y, text, color):
    """마지막 점 옆에 계열 이름을 직접 적는다 - 색만으로 구분하지 않도록."""
    ax.annotate(text, xy=(x, y), xytext=(6, 0), textcoords="offset points",
                va="center", fontsize=9, color=color, fontweight="medium")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="loadtest/results/results.csv")
    ap.add_argument("--label", help="이 라벨의 실험만 그린다 (없으면 가장 최근 run_id)")
    ap.add_argument("--out", help="PNG 저장 경로")
    args = ap.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.exists():
        print(f"결과 파일이 없습니다: {csv_path}", file=sys.stderr)
        print("먼저 .\\loadtest\\run-experiment.ps1 을 실행하세요.", file=sys.stderr)
        return 1

    df = pd.read_csv(csv_path, encoding="utf-8-sig")
    if args.label:
        df = df[df["label"] == args.label]
        if df.empty:
            print(f"'{args.label}' 라벨의 결과가 없습니다. 있는 라벨: "
                  f"{sorted(pd.read_csv(csv_path, encoding='utf-8-sig')['label'].unique())}", file=sys.stderr)
            return 1
    # 같은 라벨을 여러 번 돌렸다면 가장 최근 실행만 쓴다.
    latest = df["run_id"].max()
    df = df[df["run_id"] == latest]

    runs = df[df["kind"] == "run"].copy()
    med = df[df["kind"] == "median"].copy().sort_values("value")
    if med.empty:
        print("중간값 행이 없습니다.", file=sys.stderr)
        return 1

    sweep = med["value"].tolist()
    label = med["label"].iloc[0]
    sweep_name = med["sweep"].iloc[0]
    first = med.iloc[0]
    # 스윕한 파라미터는 고정값이 아니므로 캡션에서 뺀다.
    all_params = {"VUS": first.vus, "SEAT_COUNT": first.seat_count, "DB_POOL_SIZE": first.pool_size}
    all_params.pop(sweep_name, None)
    fixed = "  ".join([f"DURATION={first.duration}"] + [f"{k}={v}" for k, v in all_params.items()])

    fig, axes = plt.subplots(3, 1, figsize=(9, 10), sharex=True,
                             layout="constrained")

    # ── 1. 처리량 ────────────────────────────────────────────────────────
    ax = axes[0]
    ax.scatter(runs["value"], runs["tps"], s=18, color=RUN_DOT, alpha=0.55,
               zorder=2, label="개별 회차")
    ax.plot(sweep, med["tps"], color=THROUGHPUT, linewidth=2, marker="o",
            markersize=6, zorder=3, label="중간값")
    label_end(ax, sweep[-1], med["tps"].iloc[-1], "TPS", THROUGHPUT)
    style_axis(ax, "처리량 (req/s)")
    ax.set_title(f"{label}  —  {sweep_name} 스윕", fontsize=13, fontweight="semibold",
                 color=INK, loc="left", pad=14)
    ax.legend(frameon=False, fontsize=8, loc="best")

    # ── 2. 응답 시간 백분위 ───────────────────────────────────────────────
    ax = axes[1]
    for col, name in (("p50_ms", "p50"), ("p95_ms", "p95"), ("p99_ms", "p99")):
        ax.plot(sweep, med[col], color=LATENCY[col], linewidth=2, marker="o",
                markersize=5, label=name, zorder=3)
        label_end(ax, sweep[-1], med[col].iloc[-1], name, LATENCY[col])
        ax.scatter(runs["value"], runs[col], s=12, color=RUN_DOT, alpha=0.4, zorder=2)
    style_axis(ax, "응답 시간 (ms)")
    ax.legend(frameon=False, fontsize=8, loc="best", ncol=3)

    # ── 3. 초과 예약 ─────────────────────────────────────────────────────
    ax = axes[2]
    ax.scatter(runs["value"], runs["excess_reservations"], s=18, color=RUN_DOT,
               alpha=0.55, zorder=2)
    ax.plot(sweep, med["excess_reservations"], color=CRITICAL, linewidth=2,
            marker="o", markersize=6, zorder=3)
    label_end(ax, sweep[-1], med["excess_reservations"].iloc[-1], "초과 예약", CRITICAL)
    ax.axhline(0, color=GRID, linewidth=1)
    style_axis(ax, "초과 예약 (건)")
    ax.set_xlabel(sweep_name, fontsize=9)
    ax.set_xticks(sweep)
    ax.set_xticklabels([str(v) for v in sweep])

    fig.text(0.012, 0.012, f"고정: {fixed}   ·   실행 {latest}   ·   중간값 3회 중",
             fontsize=8, color=INK_MUTED)

    out = Path(args.out) if args.out else csv_path.parent / f"{label}.png"
    fig.savefig(out, dpi=160, facecolor=SURFACE)
    print(f"저장됨: {out}")

    # 콘솔 요약
    print()
    print(f"{sweep_name:>12}  {'TPS':>10}  {'p50':>8}  {'p95':>8}  {'p99':>8}  {'초과예약':>8}  {'편차%':>7}")
    for _, r in med.iterrows():
        spread = r.get("tps_spread_pct", "")
        print(f"{r.value:>12}  {r.tps:>10.1f}  {r.p50_ms:>8.2f}  {r.p95_ms:>8.2f}  "
              f"{r.p99_ms:>8.2f}  {r.excess_reservations:>8.0f}  {spread:>7}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
