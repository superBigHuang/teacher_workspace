# -*- coding: utf-8 -*-
"""
神鹿素材定稿 v2：rembg AI 抠图
- 输入：神鹿/<阶段>/神秘龙蛋.png（蛋/幼体取候选①）
- 输出：cutout/dragon_egg.png ... dragon_5.png，真透明底
- 棋盘格假背景 + 右下角水印均作为背景被移除
"""
import os
from rembg import remove, new_session
from PIL import Image

base = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(base, '神鹿')
out = os.path.join(base, 'cutout')
os.makedirs(out, exist_ok=True)

picks = [
    (os.path.join(src, '蛋', '神秘龙蛋.png'),   'dragon_egg.png'),
    (os.path.join(src, '幼体', '神秘龙蛋.png'), 'dragon_1.png'),
    (os.path.join(src, '成长', '神秘龙蛋.png'), 'dragon_2.png'),
    (os.path.join(src, '成熟', '神秘龙蛋.png'), 'dragon_3.png'),
    (os.path.join(src, '完美', '神秘龙蛋.png'), 'dragon_4.png'),
    (os.path.join(src, '神兽', '神秘龙蛋.png'), 'dragon_5.png'),
]

# u2net 通用显著性模型，对毛绒/云雾边缘友好；会话复用只加载一次
session = new_session('u2net')

for f, name in picks:
    im = Image.open(f).convert('RGBA')
    cut = remove(im, session=session,
                 alpha_matting=True, alpha_matting_foreground_threshold=240,
                 alpha_matting_background_threshold=15, alpha_matting_erode_size=8)
    cut.save(os.path.join(out, name))
    print(f'{name} done')
print('ALL DONE')
