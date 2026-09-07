"""락 전략 비교 그래프.

X축은 좌석 수(로그 스케일). 좌석이 적을수록 경합이 심하다.
선은 3회 측정의 중간값, 옅은 점은 개별 회차다. 점이 벌어진 구간에서는
선 위의 작은 차이를 결론으로 쓸 수 없다.

실행:
    python loadtest/plot_strategies.py
"""

import argparse
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"
GRID = "#e3e2df"

# 대조군은 동료가 아니라 기준선이라 무채색 점선으로 둔다.
BASELINE = "#8a8983"
# 전략 4종은 카테고리 팔레트 슬롯. 검증 통과(인접 CVD ΔE 9.2).
COLORS = {
    "none":        BASELINE,
    "pessimistic": "#2a78d6",
    "optimistic":  "#eb6834",
    "atomic":      "#1baf7a",
    "redis":       "#4a3aa7",
}
LABELS = {
    "none": "락 없음", "pessimistic": "비관적", "optimistic": "낙관적",
    "atomic": "원자적 CAS", "redis": "Redis",
}
ORDER = ["none", "pessimistic", "optimistic", "atomic", "redis"]

plt.rcParams.update({
    "font.family": ["Malgun Gothic", "DejaVu Sans"],
    "axes.unicode_minus": False,
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": INK_MUTED,
    "text.color": INK, "xtick.color": INK_MUTED, "ytick.color": INK_MUTED,
    "grid.color": GRID, "font.size": 10,
})


def style(ax, ylabel, title, note=None):
    ax.set_ylabel(ylabel, fontsize=9)
    ax.set_title(title, fontsize=11, fontweight="semibold", color=INK, loc="left", pad=10)
    if note:
        ax.text(0.0, 1.0, note, transform=ax.transAxes, fontsize=8,
                color=INK_MUTED, va="bottom", ha="left")
    ax.grid(True, axis="y", linewidth=0.8, alpha=0.9)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.spines["left"].set_color(GRID)
    ax.spines["bottom"].set_color(GRID)


def noise_pct(runs, field):
    """회차 간 편차의 중앙값. 이 값보다 작은 차이는 구분할 수 없다."""
    spreads = []
    for (_, _), group in runs.groupby(["strategy", "value"]):
        v = sorted(group[field])
        if len(v) >= 3 and statistics.median(v) > 0:
            spreads.append((v[-1] - v[0]) / statistics.median(v) * 100)
    return statistics.median(spreads) if spreads else 0.0


def draw(ax, med, runs, field, seats):
    ends = []
    for strategy in ORDER:
        m = med[med["strategy"] == strategy].sort_values("value")
        if m.empty:
            continue
        r = runs[runs["strategy"] == strategy]
        ax.scatter(r["value"], r[field], s=10, color=COLORS[strategy], alpha=0.28, zorder=2)
        ax.plot(m["value"], m[field], color=COLORS[strategy], linewidth=2,
                marker="o", markersize=4.5, zorder=3,
                linestyle="--" if strategy == "none" else "-",
                label=LABELS[strategy])
        ends.append([float(m[field].iloc[-1]), strategy])

    # 선이 수렴하는 구간에서는 끝 라벨이 겹친다. 최소 간격을 두고 밀어낸다.
    ends.sort()
    lo = min(e[0] for e in ends)
    hi = max(e[0] for e in ends)
    gap = max((hi - lo) * 0.09, (hi if hi else 1) * 0.055)
    for i in range(1, len(ends)):
        if ends[i][0] - ends[i - 1][0] < gap:
            ends[i][0] = ends[i - 1][0] + gap

    x_end = seats[-1]
    for y, strategy in ends:
        m = med[med["strategy"] == strategy].sort_values("value")
        ax.annotate(LABELS[strategy], xy=(x_end, m[field].iloc[-1]),
                    xytext=(x_end * 1.25, y), textcoords="data",
                    va="center", ha="left", fontsize=8.5,
                    color=COLORS[strategy], fontweight="medium",
                    arrowprops=dict(arrowstyle="-", color=COLORS[strategy],
                                    linewidth=0.7, alpha=0.5,
                                    shrinkA=2, shrinkB=2))
    ax.set_xscale("log")
    ax.set_xticks(seats)
    ax.set_xticklabels([str(s) for s in seats])
    ax.set_xlim(seats[0] * 0.8, seats[-1] * 3.4)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="loadtest/results/contention.csv")
    ap.add_argument("--out", default="loadtest/results/lock-strategies.png")
    args = ap.parse_args()

    path = Path(args.csv)
    if not path.exists():
        print(f"결과 파일이 없습니다: {path}", file=sys.stderr)
        return 1

    df = pd.read_csv(path, encoding="utf-8-sig")
    df = df[df["run_id"] == df["run_id"].max()]
    runs = df[df["kind"] == "run"].copy()
    med = df[df["kind"] == "median"].copy()
    seats = sorted(med["value"].unique())

    n50 = noise_pct(runs, "p50_ms")
    n95 = noise_pct(runs, "p95_ms")

    fig, axes = plt.subplots(3, 1, figsize=(9.5, 12), sharex=True, layout="constrained")

    draw(axes[0], med, runs, "p50_ms", seats)
    axes[0].legend(frameon=False, fontsize=8.5, loc="upper right", ncol=5,
                   handlelength=1.6, columnspacing=1.2)
    style(axes[0], "p50 (ms)", "응답 시간 중간값",
          f"회차 간 편차 {n50:.0f}% · 이보다 작은 차이는 구분 불가")

    draw(axes[1], med, runs, "p95_ms", seats)
    style(axes[1], "p95 (ms)", "응답 시간 상위 5%",
          f"회차 간 편차 {n95:.0f}% · 꼬리 통계라 더 불안정하다")

    draw(axes[2], med, runs, "excess_reservations", seats)
    style(axes[2], "초과 예약 (건)", "정합성",
          "락을 건 넷은 전 구간 0. 대조군만 좌석을 중복 판매한다")
    axes[2].set_xlabel("좌석 수  (왼쪽일수록 경합이 심하다 · 로그 스케일)", fontsize=9)

    vus = int(med["vus"].iloc[0])
    fig.suptitle(f"락 전략별 경합 강도 스윕  ·  동시 사용자 {vus}명  ·  3회 중간값",
                 fontsize=13, fontweight="semibold", ha="left", x=0.055)

    out = Path(args.out)
    fig.savefig(out, dpi=160, facecolor=SURFACE)
    print(f"저장됨: {out}")

    print(f"\np50 노이즈 {n50:.1f}%   p95 노이즈 {n95:.1f}%")
    print(f"\n{'좌석':>6} " + "".join(f"{LABELS[s]:>12}" for s in ORDER))
    for n in seats:
        line = f"{n:>6} "
        for s in ORDER:
            row = med[(med["strategy"] == s) & (med["value"] == n)]
            line += f"{row['p50_ms'].iloc[0]:>12.1f}" if not row.empty else f"{'-':>12}"
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
