# 把 IconMCP SVG 打包成 icons.js（设计稿用）；去掉固定宽高，保留 viewBox 与描边
import json, re, sys
from pathlib import Path
src = Path(sys.argv[1]); out = Path(sys.argv[2])
ALIAS = {'x': 'close', 'alert': 'warning-triangle'}
def load(style):
    d = {}
    for f in sorted((src / style).glob('*.svg')):
        s = f.read_text()
        s = re.sub(r'\s(width|height)="[^"]*"', '', s, count=2)
        d[f.stem] = s.strip()
    for k, v in ALIAS.items():
        if v in d: d[k] = d[v]
    return d
out.write_text('window.ICONS=' + json.dumps(load('outline'), ensure_ascii=False) + ';\nwindow.ICONS_SOLID=' + json.dumps(load('solid'), ensure_ascii=False) + ';\n')
print(len(load('outline')), 'outline', len(load('solid')), 'solid')
