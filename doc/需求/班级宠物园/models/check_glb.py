# -*- coding: utf-8 -*-
"""检查 GLB 文件的骨骼动画信息：解析 GLB 首个 JSON chunk，输出动画数量/名称/骨骼数。"""
import json, struct, glob, os, sys

def inspect(path):
    with open(path, 'rb') as f:
        data = f.read()
    magic, version, total = struct.unpack('<4sII', data[:12])
    if magic != b'glTF':
        return None
    off = 12
    js = None
    while off < len(data):
        clen, ctype = struct.unpack('<I4s', data[off:off+8])
        chunk = data[off+8:off+8+clen]
        if ctype == b'JSON':
            js = json.loads(chunk.decode('utf-8'))
            break
        off += 8 + clen
    return js

for p in sorted(glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), '*.glb'))):
    js = inspect(p)
    name = os.path.basename(p)
    if js is None:
        print(f'{name:16s} NOT_GLTF')
        continue
    anims = js.get('animations', [])
    anim_names = [a.get('name', '?') for a in anims]
    skins = len(js.get('skins', []))
    nodes = len(js.get('nodes', []))
    meshes = len(js.get('meshes', []))
    print(f'{name:16s} anims={len(anims):2d} skins={skins} nodes={nodes:3d} meshes={meshes:2d}  {anim_names}')
