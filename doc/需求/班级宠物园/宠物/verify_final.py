# -*- coding: utf-8 -*-
"""验证 final/：上排全图缩略，下排右下角 300px 特写（确认水印已清）。"""
import glob, os
from PIL import Image, ImageDraw

base = os.path.dirname(os.path.abspath(__file__))
files = sorted(glob.glob(os.path.join(base, 'final', '*.png')))
cell = 360
cols = len(files)
sheet = Image.new('RGB', (cols*cell, 2*(cell+30)), 'white')
draw = ImageDraw.Draw(sheet)
for i, f in enumerate(files):
    im = Image.open(f).convert('RGBA')
    bg = Image.new('RGBA', im.size, (255, 255, 255, 255))
    full = Image.alpha_composite(bg, im).convert('RGB')
    thumb = full.copy(); thumb.thumbnail((cell-10, cell-10))
    sheet.paste(thumb, (i*cell+(cell-thumb.width)//2, 30+(cell-thumb.height)//2))
    w, h = full.size
    corner = full.crop((w-300, h-300, w, h)).resize((cell-10, cell-10))
    sheet.paste(corner, (i*cell+5, (cell+30)+30))
    draw.text((i*cell+8, 8), os.path.basename(f), fill='black')
    draw.text((i*cell+8, (cell+30)+8), 'right-bottom', fill='red')
out = os.path.join(base, 'verify.jpg')
sheet.save(out, quality=92)
print(out)
