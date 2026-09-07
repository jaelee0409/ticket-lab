"""커넥션 풀 크기 스윕 그래프.

X축은 풀 크기(로그 스케일). 패널은 X축을 공유한다.

이중 축(왼쪽 TPS / 오른쪽 p99)을 쓰지 않는 이유: 두 축의 눈금은 그리는 사람이
정하므로 두 선이 교차하는 지점을 임의로 옮길 수 있다. 보는 사람은 그 교차점을
의미 있는 지점으로 읽는다. 같은 정보를 패널로 쌓으면 그 착시가 생기지 않는다.

실행:
    python loadtest/plot_pool.py
"""

import argparse
import statistics
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"
GRID = "#e3e2df"

# 코어 수는 두 단계뿐이라 같은 계열의 명도 차이로는 ΔE 15 를 못 넘는다.
# (검증 스크립트로 확인) 서로 다른 색상 슬롯을 쓴다.
CORE_COLORS = {2: "#1baf7a", 4: "#2a78d6"}
# 대기가 어디에 있는지를 보여주는 패널. 계열색과 겹치지 않는 두 슬롯.
WAIT_COLOR = "#eb6834"   # 풀 밖에서 기다린 시간
HOLD_COLOR = "#4a3aa7"   # 커넥션을 쥐고 있던 시간
KNEE = INK_MUTED         # 무릎 표시는 주석이지 계열이 아니다

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
    for _, group in runs.groupby(["cores", "value"]):
        v = sorted(group[field].dropna())
        if len(v) >= 3 and statistics.median(v) > 0:
            spreads.append((v[-1] - v[0]) / statistics.median(v) * 100)
    return statistics.median(spreads) if spreads else 0.0


def find_knee(m, noise):
    """다음 단계로 넘어가도 노이즈 이상 나아지지 않는 첫 지점.

    "최고 TPS 지점"을 최적점으로 잡으면 안 된다. 고원이 미세하게 우상향하면
    측정할 수 없는 차이를 근거로 가장 큰 풀을 고르게 된다. 실제로 알고 싶은
    것은 더 줘도 값을 못 하는 지점이다.
    """
    rows = m.sort_values("value").reset_index(drop=True)
    for i in range(len(rows) - 1):
        gain = (rows.loc[i + 1, "tps"] / rows.loc[i, "tps"] - 1) * 100
        if gain < noise:
            return int(rows.loc[i, "value"])
    return int(rows["value"].iloc[-1])


def draw(ax, med, runs, field, core_list, knees=None):
    for cores in core_list:
        m = med[med["cores"] == cores].sort_values("value")
        r = runs[runs["cores"] == cores]
        if m.empty:
            continue
        ax.scatter(r["value"], r[field], s=10, color=CORE_COLORS[cores], alpha=0.28, zorder=2)
        ax.plot(m["value"], m[field], color=CORE_COLORS[cores], linewidth=2,
                marker="o", markersize=5, zorder=3, label=f"DB 코어 {cores}개")
    if knees:
        for x in sorted(set(knees.values())):
            ax.axvline(x, color=KNEE, linewidth=1, linestyle=":", alpha=0.55, zorder=1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="loadtest/results/pool.csv")
    ap.add_argument("--out", default="loadtest/results/pool-size.png")
    args = ap.parse_args()

    path = Path(args.csv)
    if not path.exists():
        print(f"결과 파일이 없습니다: {path}", file=sys.stderr)
        return 1

    df = pd.read_csv(path, encoding="utf-8-sig")
    df["cores"] = df["label"].str.extract(r"(\d+)코어").astype(float)
    df = df.dropna(subset=["cores"])
    df["cores"] = df["cores"].astype(int)

    runs = df[df["kind"] == "run"].copy()
    med = df[df["kind"] == "median"].copy()
    core_list = sorted(med["cores"].unique())
    pools = sorted(med["value"].unique())

    n_tps = noise_pct(runs, "tps")
    n_p99 = noise_pct(runs, "p99_ms")
    knees = {c: find_knee(med[med["cores"] == c], n_tps) for c in core_list}

    fig, axes = plt.subplots(4, 1, figsize=(9.5, 14), sharex=True, layout="constrained")

    # ── 1. 처리량
    draw(axes[0], med, runs, "tps", core_list, knees)
    axes[0].legend(frameon=False, fontsize=8.5, loc="lower right", ncol=len(core_list))
    style(axes[0], "TPS (초당 요청)", "처리량 — 무릎을 넘으면 더 줘도 값을 못 한다",
          f"회차 간 편차 {n_tps:.1f}% · 점선은 다음 단계 이득이 그 아래로 떨어지는 지점")
    # 두 조건의 무릎이 같은 값이면 라벨 하나만 그린다. 같은 자리에 두 번
    # 찍으면 서로를 덮어 읽을 수 없게 된다.
    for x in sorted(set(knees.values())):
        owners = [c for c in core_list if knees[c] == x]
        color = CORE_COLORS[owners[0]] if len(owners) == 1 else KNEE
        y = max(float(med[(med["cores"] == c) & (med["value"] == x)]["tps"].iloc[0])
                for c in owners)
        axes[0].annotate(f"무릎 {x}", xy=(x, y), xytext=(8, -16),
                         textcoords="offset points", ha="left",
                         fontsize=9, color=color, fontweight="medium")

    # ── 2. 꼬리 지연 — 큰 풀의 대가는 여기서 나온다
    draw(axes[1], med, runs, "p99_ms", core_list, knees)
    style(axes[1], "p99 (ms)", "응답 시간 상위 1% — 큰 풀이 실제로 아픈 곳",
          f"회차 간 편차 {n_p99:.0f}% · 코어가 적을수록 오른쪽 끝이 더 나빠진다")
    # 꼬리가 다시 나빠지기 시작하는 지점. 처리량에는 안 보이고 여기서만 보인다.
    # 두 선의 최저점이 가까워 라벨이 서로/축 밖으로 겹친다. 위아래로 나눈다.
    for i, cores in enumerate(core_list):
        m = med[med["cores"] == cores].sort_values("value").reset_index(drop=True)
        worst = m["p99_ms"].idxmin()
        if worst < len(m) - 1:
            x0 = float(m.loc[worst, "value"]); y0 = float(m.loc[worst, "p99_ms"])
            rise = (m["p99_ms"].iloc[-1] / y0 - 1) * 100
            dy = -22 if i == 0 else 16
            axes[1].annotate(f"여기부터 +{rise:.0f}%", xy=(x0, y0), xytext=(-10, dy),
                             textcoords="offset points", ha="right", fontsize=8.5,
                             color=CORE_COLORS[cores], fontweight="medium",
                             arrowprops=dict(arrowstyle="-", color=CORE_COLORS[cores],
                                             linewidth=0.7, alpha=0.5, shrinkA=1, shrinkB=3))
    axes[1].margins(y=0.16)

    # ── 3. 이 실험의 결론
    ref = med[med["cores"] == max(core_list)].sort_values("value")
    x = ref["value"].to_numpy()
    wait = ref["acquire_ms"].to_numpy()
    hold = ref["usage_ms"].to_numpy()
    axes[2].fill_between(x, 0, wait, color=WAIT_COLOR, alpha=0.85,
                         linewidth=0, label="풀 밖에서 기다린 시간")
    axes[2].fill_between(x, wait, wait + hold, color=HOLD_COLOR, alpha=0.85,
                         linewidth=0, label="커넥션을 쥐고 있던 시간")
    # 겹치는 면 사이의 2px 배경색 간격
    axes[2].plot(x, wait, color=SURFACE, linewidth=2, zorder=3)
    axes[2].plot(x, wait + hold, color=INK_MUTED, linewidth=1.4, zorder=4,
                 linestyle="--", label="합계")
    axes[2].legend(frameon=False, fontsize=8.5, loc="upper right", ncol=3)
    style(axes[2], "요청당 시간 (ms)", "대기는 사라지지 않는다 — 자리를 옮길 뿐이다",
          f"DB 코어 {max(core_list)}개 기준 · 풀을 40배 키워도 합계는 그대로다")

    # ── 4. DB 가 정말 병목이었는지
    draw(axes[3], med, runs, "db_cpu_pct", core_list, knees)
    style(axes[3], "DB CPU (%)", "PostgreSQL 컨테이너 CPU",
          "코어 4개면 상한 400%, 2개면 200%. 닿지 않으면 DB 는 병목이 아니다")
    axes[3].set_xlabel("커넥션 풀 크기  (로그 스케일)", fontsize=9)
    axes[3].set_xscale("log")
    axes[3].set_xticks(pools)
    axes[3].set_xticklabels([str(p) for p in pools])
    axes[3].set_xlim(pools[0] * 0.85, pools[-1] * 1.18)

    vus = int(med["vus"].iloc[0])
    fig.suptitle(f"커넥션 풀 크기 스윕  ·  동시 사용자 {vus}명  ·  3회 중간값",
                 fontsize=13, fontweight="semibold", ha="left", x=0.055)

    out = Path(args.out)
    fig.savefig(out, dpi=160, facecolor=SURFACE)
    print(f"저장됨: {out}")

    print(f"\nTPS 노이즈 {n_tps:.1f}%   p99 노이즈 {n_p99:.1f}%")
    for cores in core_list:
        m = med[med["cores"] == cores].sort_values("value")
        print(f"\n── DB 코어 {cores}개   공식 예측 {cores * 2 + 1}   측정 무릎 {knees[cores]}")
        print(f"{'풀':>6}{'TPS':>9}{'p99':>8}{'획득대기':>10}{'점유':>8}"
              f"{'합계':>8}{'DB연결':>8}{'DB CPU':>8}")
        for _, row in m.iterrows():
            print(f"{int(row['value']):>6}{row['tps']:>9.0f}{row['p99_ms']:>8.1f}"
                  f"{row['acquire_ms']:>10.2f}{row['usage_ms']:>8.2f}"
                  f"{row['acquire_ms'] + row['usage_ms']:>8.2f}"
                  f"{row['db_conns']:>8.0f}{row['db_cpu_pct']:>8.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
