import re, sys
css = open(sys.argv[1]).read()
def block(sel):
    m = re.search(re.escape(sel) + r'\s*\{(.*?)\}', css, re.S); return dict(re.findall(r'--([\w-]+):\s*(#[0-9A-Fa-f]{6})', m.group(1)))
L = block(':root'); D = {**L, **block('[data-theme="dark"]')}
def lum(h):
    r,g,b=[int(h[i:i+2],16)/255 for i in (1,3,5)]; f=lambda c: c/12.92 if c<=0.03928 else ((c+0.055)/1.055)**2.4
    return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b)
def cr(a,b): x,y=sorted([lum(a),lum(b)],reverse=True); return (x+0.05)/(y+0.05)
pairs=[('text','bg','正文',4.5),('text','card','正文',4.5),('text2','bg','次要文字',4.5),('text2','card','次要文字',4.5),('brand','card','品牌文字/链接',4.5),('brand','bg','品牌文字/链接',4.5),('brand','brand-soft','选中项文字',4.5),
 ('on-brand','brand','主按钮文字',4.5),('neg','card','负值金额/错误',4.5),('neg','neg-soft','逾期徽标',4.5),('warn','warn-soft','7 天内徽标',4.5),('pos','card','成功图标',3),
 ('cat-cash','cat-cash-soft','分类图标',3),('cat-fixed','cat-fixed-soft','分类图标',3),('cat-investment','cat-investment-soft','分类图标',3),('cat-receivable','cat-receivable-soft','分类图标',3),('cat-debt','cat-debt-soft','分类图标',3),('sep','card','分隔线（装饰，不计）',1)]
print('| 前景 | 背景 | 用途 | 浅色 | 深色 | 要求 |\n| --- | --- | --- | --- | --- | --- |')
bad=0
for fg,bg,use,req in pairs:
    a=cr(L[fg],L[bg]); b=cr(D[fg],D[bg]); ok = a>=req and b>=req
    bad += not ok
    print(f'| {fg} | {bg} | {use} | {L[fg]}/{L[bg]} = {a:.2f} | {D[fg]}/{D[bg]} = {b:.2f} | ≥{req} {"✓" if ok else "✗"} |')
print(f'\n不达标：{bad}', file=sys.stderr)
