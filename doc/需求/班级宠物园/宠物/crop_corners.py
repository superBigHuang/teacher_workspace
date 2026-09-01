# -*- coding: utf-8 -*-
"""裁出各图右下角 360x360，拼小样检查水印精确位置与背景内容。"""
import glob, os
from PIL import Image, ImageDraw

base = os.path.dirname(os.path.abspath(__file__))
files = [
    ('蛋', os.path.join(base, '神鹿', '蛋', '神秘龙蛋.png')),
    ('幼体', os.path.join(base, '神鹿', '幼体', '神秘龙蛋.png')),
    ('成长', os.path.join(base, '神鹿', '成长', '神秘龙蛋.png')),
    ('成熟', os.path.join(base, '神鹿', '成熟', '神秘龙蛋.png')),
    ('完美', os.path.join(base, '神鹿', '完美', '神秘龙蛋.png')),
    ('神兽', os.path.join(base, '神鹿', '神兽', '神秘龙蛋.png')),
]
cell = 360
cols = 3
rows = 2
sheet = Image.new('RGB', (cols*cell, rows*(cell+30)), 'gray')
draw = ImageDraw.Draw(sheet)
for i, (label, f) in enumerate(files):
    im = Image.open(f).convert('RGBA')
    w, h = im.size
    crop = im.crop((w-360, h-360, w, h))
    bg = Image.new('RGBA', (360, 360), (255, 255, 255, 255))
    crop = Image.alpha_composite(bg, crop).convert('RGB')
    r, c = divmod(i, cols)
    sheet.paste(crop, (c*cell, r*(cell+30)+30))
    draw.text((c*cell+10, r*(cell+30)+8), f'{label} 右下角360px (图{w}x{h})', fill='yellow')
out = os.path.join(base, 'corners.jpg')
sheet.save(out, quality=92)
print(out)
