# -*- coding: utf-8 -*-
"""
神鹿（云小龙）素材定稿处理：
1. 去豆包水印：右下角固定矩形区域 alpha 清零（背景为透明，无损）
2. 规范化命名输出到 final/：dragon_egg / dragon_1..5
"""
import os
from PIL import Image

base = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(base, '神鹿')
out = os.path.join(base, 'final')
os.makedirs(out, exist_ok=True)

# (源文件, 输出名)；蛋/幼体默认取候选①，换候选改这里
picks = [
    (os.path.join(src, '蛋', '神秘龙蛋.png'),   'dragon_egg.png'),
    (os.path.join(src, '幼体', '神秘龙蛋.png'), 'dragon_1.png'),
    (os.path.join(src, '成长', '神秘龙蛋.png'), 'dragon_2.png'),
    (os.path.join(src, '成熟', '神秘龙蛋.png'), 'dragon_3.png'),
    (os.path.join(src, '完美', '神秘龙蛋.png'), 'dragon_4.png'),
    (os.path.join(src, '神兽', '神秘龙蛋.png'), 'dragon_5.png'),
]

# 水印矩形（2048 图实测：右下 豆包 logo），按比例适配任意尺寸
WM_X1, WM_Y1, WM_X2, WM_Y2 = 1740, 1860, 1990, 2030
REF = 2048.0

for f, name in picks:
    im = Image.open(f).convert('RGBA')
    w, h = im.size
    sx, sy = w / REF, h / REF
    box = (int(WM_X1*sx), int(WM_Y1*sy), int(WM_X2*sx), int(WM_Y2*sy))
    # 清 alpha：把水印区域像素全部置透明
    from PIL import ImageDraw
    ImageDraw.Draw(im).rectangle(box, fill=(0, 0, 0, 0))
    dst = os.path.join(out, name)
    im.save(dst)
    print(f'{name}: {w}x{h} -> 水印已清 {box}')
