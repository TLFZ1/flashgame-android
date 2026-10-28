#!/usr/bin/env python3
"""从 Noto Sans CJK JP 生成行高度量收紧的衍生字体（SIL OFL 1.1，保留原版权声明）。

为什么需要：Ruffle 用字体自带的 ascent/descent 计算设备文本行高。
Noto CJK 是 1.448 em/行，而 Flash 时代的 MS Gothic/MS PGothic 是 1.0 em/行，
导致同样的文本框在 Ruffle 里少放好几行、末段被裁掉。
把 hhea/OS2 的度量改成 0.84 / 0.10 em（合计 0.94 em）后，实测与 Flash Player 排版一致。

用法：python tools/make-font.py <输入ttf> <输出ttf>
"""
import sys
from fontTools.ttLib import TTFont

SRC = sys.argv[1] if len(sys.argv) > 1 else 'NotoSansCJKjp-Regular.ttf'
DST = sys.argv[2] if len(sys.argv) > 2 else 'app/assets/FlashGameCJK.ttf'
ASCENT, DESCENT = 0.84, 0.10
FAMILY = 'Flash Game CJK'
FULL = 'Flash Game CJK Regular'
PS = 'FlashGameCJK-Regular'

f = TTFont(SRC)
upem = f['head'].unitsPerEm
asc, desc = int(round(upem * ASCENT)), -int(round(upem * DESCENT))

f['hhea'].ascent, f['hhea'].descent, f['hhea'].lineGap = asc, desc, 0
o = f['OS/2']
o.sTypoAscender, o.sTypoDescender, o.sTypoLineGap = asc, desc, 0
o.usWinAscent, o.usWinDescent = asc, -desc

for rec in list(f['name'].names):
    if rec.nameID in (1, 16):
        f['name'].setName(FAMILY, rec.nameID, rec.platformID, rec.platEncID, rec.langID)
    elif rec.nameID == 4:
        f['name'].setName(FULL, rec.nameID, rec.platformID, rec.platEncID, rec.langID)
    elif rec.nameID == 6:
        f['name'].setName(PS, rec.nameID, rec.platformID, rec.platEncID, rec.langID)
    elif rec.nameID == 17:
        f['name'].setName('Regular', rec.nameID, rec.platformID, rec.platEncID, rec.langID)
f.save(DST)
print(f'wrote {DST} (asc {asc}, desc {desc}) full name = {FULL}')
