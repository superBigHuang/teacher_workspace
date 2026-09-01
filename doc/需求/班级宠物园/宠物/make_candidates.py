# -*- coding: utf-8 -*-
"""拼蛋4张+幼体4张候选小样，便于挑选。"""
import glob, os
from PIL import Image, ImageDraw

base = os.path.dirname(os.path.abspath(__file__))
groups = [('蛋', os.path.join(base, '神鹿', '蛋')), ('幼体', os.path.join(base, '神鹿', '幼体'))]
cell = 480
cols = 4
rows = 2
sheet = Image.new('RGB', (cols*cell, rows*(cell+40)), 'white')
draw = ImageDraw.Draw(sheet)

for r, (label, d) in enumerate(groups):
    files = sorted(glob.glob(os.path.join(d, '*.png')))
    for c, f in enumerate(files[:4]):
        im = Image.open(f).convert('RGBA')
        bg = Image.new('RGBA', im.size, (255, 255, 255, 255))
        im = Image.alpha_composite(bg, im).convert('RGB')
        im.thumbnail((cell-20, cell-20))
        x = c*cell + (cell-im.width)//2
        y = r*(cell+40) + 40 + (cell-im.height)//2
        sheet.paste(im, (x, y))
        draw.text((c*cell+12, r*(cell+40)+12), f'{label}{c+1}: {os.path.basename(f)}', fill='black')

out = os.path.join(base, 'candidates.jpg')
sheet.save(out, quality=90)
print(out)
