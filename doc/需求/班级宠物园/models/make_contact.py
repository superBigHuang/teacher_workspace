# -*- coding: utf-8 -*-
"""把 posters/*.jpg 拼成带文件名标签的网格接触印样图，用于一次性验货。"""
import glob, os, sys
from PIL import Image, ImageDraw

base = os.path.dirname(os.path.abspath(__file__))
posters = sorted(glob.glob(os.path.join(base, 'posters', '*.jpg')))
cols, rows = 5, 5
cell_w, cell_h = 360, 360
label_h = 34
sheet = Image.new('RGB', (cols*cell_w, rows*(cell_h+label_h)), 'white')
draw = ImageDraw.Draw(sheet)

for idx, p in enumerate(posters):
    r, c = divmod(idx, cols)
    im = Image.open(p).convert('RGB')
    im.thumbnail((cell_w-20, cell_h-20))
    x = c*cell_w + (cell_w-im.width)//2
    y = r*(cell_h+label_h) + label_h + (cell_h-im.height)//2
    sheet.paste(im, (x, y))
    draw.text((c*cell_w+10, r*(cell_h+label_h)+8), os.path.basename(p)[:-4], fill='black')

out = os.path.join(base, 'contact_sheet.jpg')
sheet.save(out, quality=88)
print(out, sheet.size)
