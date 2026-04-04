"""
Two bar charts for OpenGUI-Eval reproduction results on ScreenSpot-Pro.
- No title
- Value labels on top of each bar
- Legend: "Official" / "OpenGUI-Eval" only, upper-right
- Clean white canvas
"""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

# ---------------------------------------------------------------------------
# Color palette
# ---------------------------------------------------------------------------
BLUE_DARK = "#4A7FC1"   # Official
ORANGE    = "#E8875A"   # OpenGUI-Eval

plt.rcParams.update({
    "font.family":        "DejaVu Sans",
    "axes.spines.top":    False,
    "axes.spines.right":  False,
    "axes.spines.left":   True,
    "axes.spines.bottom": True,
    "axes.edgecolor":     "#CCCCCC",
    "axes.linewidth":     0.8,
})

BAR_WIDTH = 0.35
TICK_FS   = 11
LABEL_FS  = 12
VAL_FS    = 10


def add_labels(ax, bars, offset=0.25):
    for bar in bars:
        h = bar.get_height()
        ax.text(
            bar.get_x() + bar.get_width() / 2.0,
            h + offset,
            f"{h:.2f}",
            ha="center", va="bottom",
            fontsize=VAL_FS, fontweight="bold", color="#333333",
        )


def style_ax(ax, ylim, ylabel):
    ax.set_facecolor("white")
    ax.set_ylim(*ylim)
    ax.set_ylabel(ylabel, fontsize=LABEL_FS, color="#333333", labelpad=8)
    ax.yaxis.grid(True, color="#E8E8E8", linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    ax.tick_params(axis="x", labelsize=TICK_FS, length=0)
    ax.tick_params(axis="y", labelsize=TICK_FS - 1, length=0, colors="#555555")


LEGEND_HANDLES = [
    mpatches.Patch(color=BLUE_DARK, label="Official"),
    mpatches.Patch(color=ORANGE,    label="OpenGUI-Eval"),
]

# ===========================================================================
# Figure 1 – Closed-Source Models
# ===========================================================================
closed_models   = ["Gemini 3.0 Pro", "Seed 1.8"]
official_scores = [72.70, 73.10]
ours_scores     = [75.08, 72.80]

x = np.arange(len(closed_models))

fig1, ax1 = plt.subplots(figsize=(6, 5))
fig1.patch.set_facecolor("white")

b_off1 = ax1.bar(x - BAR_WIDTH / 2, official_scores,
                 BAR_WIDTH, color=BLUE_DARK, edgecolor="white", linewidth=1.2, zorder=3)
b_our1 = ax1.bar(x + BAR_WIDTH / 2, ours_scores,
                 BAR_WIDTH, color=ORANGE,    edgecolor="white", linewidth=1.2, zorder=3)

add_labels(ax1, b_off1)
add_labels(ax1, b_our1)

ax1.set_xticks(x)
ax1.set_xticklabels(closed_models, fontsize=TICK_FS + 1, fontweight="bold")
style_ax(ax1, ylim=(65, 82), ylabel="ScreenSpot-Pro Score")

ax1.legend(handles=LEGEND_HANDLES, frameon=True, framealpha=0.95,
           edgecolor="#DDDDDD", fontsize=TICK_FS, loc="upper right")

fig1.tight_layout(pad=1.8)
fig1.savefig("assets/closed_source_sspro.png", dpi=180,
             bbox_inches="tight", facecolor="white")
print("Saved: assets/closed_source_sspro.png")


# ===========================================================================
# Figure 2 – Open-Source Models (3 representative models)
# ===========================================================================
open_data = [
    ("GUI-G²",       47.50, 47.75),
    ("Qwen3-VL-4B",  59.50, 59.39),
    ("Qwen3-VL-8B",  54.60, 56.42),
]

labels2   = [d[0] for d in open_data]
official2 = [d[1] for d in open_data]
ours2     = [d[2] for d in open_data]

x2 = np.arange(len(labels2))

fig2, ax2 = plt.subplots(figsize=(7, 5))
fig2.patch.set_facecolor("white")

b_off2 = ax2.bar(x2 - BAR_WIDTH / 2, official2,
                 BAR_WIDTH, color=BLUE_DARK, edgecolor="white", linewidth=1.2, zorder=3)
b_our2 = ax2.bar(x2 + BAR_WIDTH / 2, ours2,
                 BAR_WIDTH, color=ORANGE,    edgecolor="white", linewidth=1.2, zorder=3)

add_labels(ax2, b_off2)
add_labels(ax2, b_our2)

ax2.set_xticks(x2)
ax2.set_xticklabels(labels2, fontsize=TICK_FS + 1, fontweight="bold")
style_ax(ax2, ylim=(35, 75), ylabel="ScreenSpot-Pro Score")

ax2.legend(handles=LEGEND_HANDLES, frameon=True, framealpha=0.95,
           edgecolor="#DDDDDD", fontsize=TICK_FS, loc="upper right")

fig2.tight_layout(pad=1.8)
fig2.savefig("assets/open_source_sspro.png", dpi=180,
             bbox_inches="tight", facecolor="white")
print("Saved: assets/open_source_sspro.png")

plt.close("all")
print("Done.")
