# -*- coding: utf-8 -*-
"""验证 cutout/：奶油色卡片背景 + 全图缩略 + 右下角特写。"""
import glob, os
from PIL import Image, ImageDraw

base = os.path.dirname(os.path.abspath(__file__))
files = sorted(glob.glob(os.path.join(base, 'cutout', '*.png')))
cell = 420
cols = len(files)
cream = (253, 244, 236, 255)  # 主题 --bg 奶油色
sheet = Image.new('RGB', (cols*cell, 2*(cell+30)), (255, 255, 255))
draw = ImageDraw.Draw(sheet)
for i, f in enumerate(files):
    im = Image.open(f).convert('RGBA')
    bg = Image.new('RGBA', im.size, cream)
    comp = Image.alpha_composite(bg, im).convert('RGB')
    thumb = comp.copy(); thumb.thumbnail((cell-10, cell-10))
    sheet.paste(thumb, (i*cell+(cell-thumb.width)//2, 30+(cell-thumb.height)//2))
    w, h = comp.size
    corner = comp.crop((w-320, h-320, w, h)).resize((cell-10, cell-10))
    sheet.paste(corner, (i*cell+5, (cell+30)+30))
    draw.text((i*cell+8, 8), os.path.basename(f), fill='black')
    draw.text((i*cell+8, (cell+30)+8), 'right-bottom (cream bg)', fill='red')
out = os.path.join(base, 'verify_cutout.jpg')
sheet.save(out, quality=92)
print(out)
