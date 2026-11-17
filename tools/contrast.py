"""Contrast table (D13 / M6): every text colour x the background it sits on, WCAG relative-luminance ratio.
Glass backgrounds are taken from the emulator screenshots (darkest / lightest measured glass), fills by value.
  python tools/contrast.py
"""
def lum(h):
    h = h.lstrip("#"); c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

def ratio(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True)
    return (la + 0.05) / (lb + 0.05)

# sampled from build/v010/S03-en-win.png etc. (5 x 5 px medians): glass #B2B1AE / #B2ABA3, cards #D5D2CE, status #C8C1B9
GLASS_DARK, GLASS_LIGHT, FILL2 = "#B2ABA3", "#B2B1AE", "#C8C1B9"
PAIRS = [
    ("Ink 正文 / 标题", "#1B1B1B", "玻璃（暗）", GLASS_DARK, 4.5),
    ("Ink 正文 / 标题", "#1B1B1B", "玻璃（亮）", GLASS_LIGHT, 4.5),
    ("InkSecondary 小字 / 说明", "#3A3F44", "玻璃（暗）", GLASS_DARK, 4.5),
    ("InkSecondary 小字 / 卡片", "#3A3F44", "状态卡（实测 #C8C1B9）", FILL2, 4.5),
    ("Ink 状态卡文字", "#1B1B1B", "状态卡（实测 #C8C1B9）", FILL2, 4.5),
    ("主按钮白字", "#FFFFFF", "Primary #2E7D5B", "#2E7D5B", 4.5),
    ("退出按钮白字", "#FFFFFF", "Error #B3261E", "#B3261E", 4.5),
    ("提示卡文字", "#4A2B0E", "SecondaryContainer #F3DEC6", "#F3DEC6", 4.5),
    ("已不可能卡文字", "#1B1B1B", "LostContainer #DDE0E3", "#DDE0E3", 4.5),
    ("不能落原因（状态卡）", "#7A1A18", "#FFE4E0", "#FFE4E0", 4.5),
    ("确认卡图标", "#0F3A28", "PrimaryContainer #CFE9DB", "#CFE9DB", 3.0),
    ("席位牌墨字", "#1B1B1B", "席位牌底 #FBF6EC", "#FBF6EC", 4.5),
    ("席位牌原因（红）", "#7A1A18", "席位牌底 #FBF6EC", "#FBF6EC", 4.5),
    ("席位牌提示（次）", "#3A3F44", "席位牌底 #FBF6EC", "#FBF6EC", 4.5),
    ("次按钮文字", "#1B1B1B", "次按钮（实测 #D0C9C2）", "#D0C9C2", 4.5),
    ("分数块文字", "#1B1B1B", "分数卡（实测 #D5D2CE）", "#D5D2CE", 4.5),
    ("页签图标（未选）", "#1B1B1B", "TabBar 胶囊（实测 #B0ABA4）", "#B0ABA4", 3.0),
    ("页签图标（选中，系统白）", "#FFFFFF", "选中底（实测 #44413D）", "#44413D", 3.0),
]
print("| 文字 | 色 | 底 | 底色 | 对比度 | 要求 | 结果 |\n|---|---|---|---|---|---|---|")
for name, fg, bgname, bg, need in PAIRS:
    r = ratio(fg, bg)
    print(f"| {name} | {fg} | {bgname} | {bg} | {r:.2f}:1 | {need}:1 | {'过' if r >= need else '不过'} |")
