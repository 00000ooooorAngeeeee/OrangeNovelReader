#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一本"大书"用于测试加载与滚动性能：400 章 + 插图。"""
import os, zlib, struct, zipfile, random

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "testdata", "big.epub"))

def png(width, height, fn):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(fn(x, y, width, height))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))

cover = png(360, 504, lambda x, y, w, h: bytes((30 + y*60//h, 40, 60)))
art = png(600, 400, lambda x, y, w, h: bytes(((x*255)//w, (y*255)//h, 120)))

SENT = [
    "剑光在雨里亮了一瞬，很快被夜色吞没。",
    "他忽然想起很多年前的一个下午，阳光斜斜地照进院子，尘埃在光里浮着，像是有自己的心思。",
    "“走吧。”她说，声音很轻，却像一块石头落进水里。",
    "街上的铺子都关了，只有一间茶楼还亮着灯，灯下坐着一个打盹的老掌柜。",
    "那一天他学会了一件事：有些话不必说出口，说了反而不好。",
    "远处的山影压在城墙上，像一只伏着不动的兽。",
    "风从窗缝里钻进来，把灯焰压得极低，屋里忽明忽暗。",
    "他把那封信折好，放进怀里，贴着心口的位置。",
]

def para(n=120):
    s = ""
    while len(s) < n:
        s += random.choice(SENT)
    return s

N = 400
chapters = []
for i in range(1, N + 1):
    title = f"第{i}章 风雪夜归人"
    body = "\n".join("<p>%s</p>" % para() for _ in range(random.randint(10, 16)))
    if i % 20 == 0:
        body += '\n<p style="text-align:center"><img src="../Images/art.png"/></p>\n'
    chapters.append((title, body))

manifest = ['    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
            '    <item id="cover" href="Images/cover.png" media-type="image/png"/>',
            '    <item id="art" href="Images/art.png" media-type="image/png"/>']
spine = []
for i in range(1, N + 1):
    manifest.append(f'    <item id="c{i}" href="Text/c{i}.xhtml" media-type="application/xhtml+xml"/>')
    spine.append(f'    <itemref idref="c{i}"/>')

opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>千山暮雪（大文件测试）</dc:title>
    <dc:creator>测试作者</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="id">urn:uuid:big-0001</dc:identifier>
    <meta name="cover" content="cover"/>
  </metadata>
  <manifest>
{chr(10).join(manifest)}
  </manifest>
  <spine toc="ncx">
{chr(10).join(spine)}
  </spine>
</package>
"""

navs = []
for i in range(1, N + 1):
    navs.append(f"""    <navPoint id="n{i}" playOrder="{i}">
      <navLabel><text>第{i}章 风雪夜归人</text></navLabel>
      <content src="Text/c{i}.xhtml"/>
    </navPoint>""")

ncx = f"""<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:big-0001"/></head>
  <docTitle><text>千山暮雪</text></docTitle>
  <navMap>
{chr(10).join(navs)}
  </navMap>
</ncx>
"""

container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with zipfile.ZipFile(OUT, "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/content.opf", opf, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/toc.ncx", ncx, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/Images/cover.png", cover, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/Images/art.png", art, compress_type=zipfile.ZIP_DEFLATED)
    for i, (title, body) in enumerate(chapters, start=1):
        xhtml = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head>
<body><h2>{title}</h2>
{body}
</body></html>"""
        z.writestr(f"OEBPS/Text/c{i}.xhtml", xhtml, compress_type=zipfile.ZIP_DEFLATED)

print("written:", OUT, os.path.getsize(OUT), "bytes,", N, "chapters")
