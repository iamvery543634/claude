"""Writes Ghost Hub's colours, theme, strings, icons, logo and helper XML. Run from anywhere."""
from pathlib import Path

APP = Path(__file__).resolve().parent.parent / "app" / "src" / "main"
RES = APP / "res"
XML = '<?xml version="1.0" encoding="utf-8"?>\n'
ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def write(rel, text, base=RES):
    p = base / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(XML + text.strip() + "\n", encoding="utf-8")


write("values/colors.xml", """
<resources>
    <color name="bg">#0F1116</color>
    <color name="panel">#171A21</color>
    <color name="card">#1D2129</color>
    <color name="card_hi">#262A34</color>
    <color name="well">#111419</color>
    <color name="stroke">#2A2F3A</color>
    <color name="accent">#72E2F7</color>
    <color name="accent_dim">#15303A</color>
    <color name="on_accent">#062129</color>
    <color name="violet">#8B7CF6</color>
    <color name="text">#EEF1F5</color>
    <color name="text2">#9AA3AF</color>
    <color name="text3">#697180</color>
    <color name="green">#6ED39C</color>
    <color name="red">#F0736E</color>
    <color name="yellow">#F4C543</color>
    <color name="warn_stroke">#5C4E1E</color>
</resources>
""")

write("values/strings.xml", """
<resources>
    <string name="app_name">Ghost Hub</string>
</resources>
""")

write("values/themes.xml", """
<resources>
    <style name="Base.Theme.GhostHub" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">@color/accent</item>
        <item name="colorOnPrimary">@color/on_accent</item>
        <item name="colorPrimaryContainer">@color/accent_dim</item>
        <item name="colorOnPrimaryContainer">@color/accent</item>
        <item name="colorSurface">@color/panel</item>
        <item name="colorOnSurface">@color/text</item>
        <item name="colorSurfaceContainer">@color/card</item>
        <item name="colorSurfaceContainerHigh">@color/card</item>
        <item name="colorSurfaceContainerHighest">@color/card_hi</item>
        <item name="colorOutline">@color/stroke</item>
        <item name="android:colorBackground">@color/bg</item>
        <item name="android:windowBackground">@color/bg</item>
        <item name="android:textColorPrimary">@color/text</item>
        <item name="android:textColorSecondary">@color/text2</item>
    </style>

    <style name="Theme.GhostHub" parent="Base.Theme.GhostHub" />
</resources>
""")

write("values-v31/themes.xml", """
<resources>
    <style name="Theme.GhostHub" parent="Base.Theme.GhostHub">
        <item name="android:windowSplashScreenBackground">@color/bg</item>
        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_launcher_fg</item>
    </style>
</resources>
""")

# FileProvider paths: only the folder where downloaded APKs land.
write("xml/file_paths.xml", f"""
<paths {ANDROID}>
    <cache-path name="downloads" path="apk/" />
</paths>
""")

ICONS = {
    "ic_refresh": "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
    "ic_download": "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z",
    "ic_check": "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
    "ic_open": "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
    "ic_settings": "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    "ic_warning": "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z",
    "ic_wifi": "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
    "ic_hub": "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,14c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    "ic_apps": "M4,8h4V4H4v4zm6,12h4v-4h-4v4zm-6,0h4v-4H4v4zm0,-6h4v-4H4v4zm6,0h4v-4h-4v4zm6,-10v4h4V4h-4zm-6,4h4V4h-4v4zm6,6h4v-4h-4v4zm0,6h4v-4h-4v4z",
}
for name, d in ICONS.items():
    write(f"drawable/{name}.xml", f"""
<vector {ANDROID}
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white" android:pathData="{d}" />
</vector>
""")

# ---- Logo: three ghost dots connected like a hub/network, in the family gradient.
NODES = [(54, 30), (32, 66), (76, 66)]  # top, bottom-left, bottom-right


def ghost(cx, cy, r):
    # A small ghost centred near (cx, cy), radius r.
    top = cy - r
    return (f"M{cx-r},{cy} A{r},{r} 0 0,1 {cx+r},{cy} L{cx+r},{cy+r} "
            f"Q{cx+r*0.7:.1f},{cy+r*1.35:.1f} {cx+r*0.35:.1f},{cy+r} "
            f"Q{cx:.1f},{cy+r*0.7:.1f} {cx-r*0.35:.1f},{cy+r} "
            f"Q{cx-r*0.7:.1f},{cy+r*1.35:.1f} {cx-r},{cy+r} Z")


def eyes(cx, cy, r):
    ex = r * 0.32
    ey = cy - r * 0.05
    er_x, er_y = r * 0.16, r * 0.22
    return (f"M{cx-ex-er_x:.1f},{ey:.1f}a{er_x:.1f},{er_y:.1f} 0 1,0 {2*er_x:.1f},0"
            f"a{er_x:.1f},{er_y:.1f} 0 1,0 {-2*er_x:.1f},0z"
            f"M{cx+ex-er_x:.1f},{ey:.1f}a{er_x:.1f},{er_y:.1f} 0 1,0 {2*er_x:.1f},0"
            f"a{er_x:.1f},{er_y:.1f} 0 1,0 {-2*er_x:.1f},0z")


def mark(gradient: bool, glow: bool = False) -> str:
    # Connecting lines between the three ghosts.
    lines = (f"M{NODES[0][0]},{NODES[0][1]} L{NODES[1][0]},{NODES[1][1]} "
             f"M{NODES[0][0]},{NODES[0][1]} L{NODES[2][0]},{NODES[2][1]} "
             f"M{NODES[1][0]},{NODES[1][1]} L{NODES[2][0]},{NODES[2][1]}")
    r = 12
    if not gradient:
        body = "".join(f'<path android:pathData="{ghost(cx, cy, r)} {eyes(cx, cy, r)}" '
                       f'android:fillType="evenOdd" android:fillColor="#FFFFFFFF" />' for cx, cy in NODES)
        return (f'<path android:pathData="{lines}" android:strokeWidth="3.5" android:strokeColor="#FFFFFFFF" '
                f'android:strokeLineCap="round" />\n    {body}')
    halo = ""
    if glow:
        halo = """<path android:pathData="M54,54m-40,0a40,40 0,1 1,80 0a40,40 0,1 1,-80 0">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="52" android:gradientRadius="40">
                <item android:offset="0" android:color="#3372E2F7" />
                <item android:offset="0.6" android:color="#148B7CF6" />
                <item android:offset="1" android:color="#008B7CF6" />
            </gradient>
        </aapt:attr>
    </path>
    """
    line_grad = """
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="30" android:startY="30" android:endX="78" android:endY="70">
                <item android:offset="0" android:color="#FF72E2F7" />
                <item android:offset="1" android:color="#FF8B7CF6" />
            </gradient>
        </aapt:attr>"""
    ghosts = ""
    for cx, cy in NODES:
        ghosts += (f'<path android:pathData="{ghost(cx, cy, r)}">'
                   f'<aapt:attr name="android:fillColor"><gradient android:type="linear" '
                   f'android:startX="{cx}" android:startY="{cy-r}" android:endX="{cx}" android:endY="{cy+r}">'
                   f'<item android:offset="0" android:color="#FFFFFFFF" />'
                   f'<item android:offset="1" android:color="#FFB9E9F7" /></gradient></aapt:attr></path>\n    '
                   f'<path android:pathData="{eyes(cx, cy, r)}" android:fillColor="#FF14203A" />\n    ')
    return (f'{halo}<path android:pathData="{lines}" android:strokeWidth="4" android:strokeLineCap="round" '
            f'android:fillColor="#00000000">{line_grad}\n    </path>\n    {ghosts}')


write("drawable/ic_launcher_bg.xml", f"""
<vector {ANDROID} {AAPT}
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FF13263A" />
                <item android:offset="0.55" android:color="#FF0B111C" />
                <item android:offset="1" android:color="#FF120E24" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
""")
write("drawable/ic_launcher_fg.xml", f"""
<vector {ANDROID} {AAPT}
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    {mark(gradient=True, glow=True)}
</vector>
""")
write("drawable/ic_launcher_mono.xml", f"""
<vector {ANDROID}
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    {mark(gradient=False)}
</vector>
""")
write("drawable/ic_logo.xml", f"""
<vector {ANDROID} {AAPT}
    android:width="56dp" android:height="56dp"
    android:viewportWidth="108" android:viewportHeight="108">
    {mark(gradient=True)}
</vector>
""")
write("mipmap-anydpi-v26/ic_launcher.xml", f"""
<adaptive-icon {ANDROID}>
    <background android:drawable="@drawable/ic_launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
""")

print("wrote", sum(1 for _ in RES.rglob("*.xml")), "resource files")
