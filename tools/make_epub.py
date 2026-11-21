#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一本结构完整的中文测试 EPUB，用于验证解析/排版/滚动。"""
import os, zlib, struct, zipfile, random

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "testdata", "sample.epub")
OUT = os.path.normpath(OUT)

# ---------------- 极简 PNG 生成（不依赖 PIL） ----------------
def png(width, height, pixel_fn):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixel_fn(x, y, width, height))
    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))

def cover_pixels(x, y, w, h):
    t = y / h
    r = int(28 + 60 * t)
    g = int(40 + 30 * t)
    b = int(52 + 24 * t)
    # 中间一个暖色方块
    if int(w * 0.28) < x < int(w * 0.72) and int(h * 0.34) < y < int(h * 0.62):
        r, g, b = 196, 88, 40
    return bytes((r, g, b))

def art_pixels(x, y, w, h):
    # 简单"插图"：灰底 + 圆
    cx, cy, rr = w // 2, h // 2, min(w, h) // 3
    d = (x - cx) ** 2 + (y - cy) ** 2
    if d < rr * rr:
        return bytes((226, 214, 198))
    return bytes((60, 66, 74))

cover = png(480, 672, cover_pixels)
art = png(720, 480, art_pixels)

# ---------------- 正文 ----------------
SENT = [
    "夜风从长街尽头卷过来，带着潮湿的凉意，把檐下的灯笼吹得斜斜晃了一下。",
    "他站在门槛边上，手指无意识地扣着门框上那道旧裂缝，像是要把这十年的光景一并抠出来。",
    "远处传来更鼓，一下，两下，声音在青石板上滚了很远才散。",
    "“你确定要这么做？”身后的人低声问。",
    "他没有回头，只说了三个字：“已经晚了。”",
    "那一年春天，城南的桃树开得格外疯，落花铺满整条巷子，踩上去没有声音。",
    "师父说，剑要走直，人要留弯，凡事留三分余地，才不至于把自己逼到绝路上。",
    "可这世上的事，往往是你留了余地，别人却不留。",
    "灯芯爆了一下，火光猛地立起来，又在下一刻缩了回去。",
    "屋子里只剩下翻书的响动，一页，又一页，像有人在数着剩下的日子。",
]

def para(n_chars=90):
    s = ""
    while len(s) < n_chars:
        s += random.choice(SENT)
    return s

def chapter_xhtml(title, idx, body, broken=False):
    if broken:
        # 故意制造未闭合标签，测试容错
        body = body.replace("</p>", "</p>", 5).replace("<p>", "<p>", 5)
        close = ""
    else:
        close = "</body>\n</html>"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>{title}</title>
<style type="text/css">p{{text-indent:2em;margin:0.6em 0;}} .center{{text-align:center;}}</style>
</head>
<body>
<h1>{title}</h1>
{body}
{close}
"""

chapters = []
toc = []

c1 = "\n".join("<p>%s</p>" % para() for _ in range(14))
chapters.append(("第一节 长街夜雨", c1))
toc.append(("第一节 长街夜雨", "chapter1.xhtml", 0))

c2 = ("\n".join("<p>%s</p>" % para() for _ in range(8))
      + '\n<p class="center"><img src="../Images/art.png" alt="插图"/></p>\n'
      + "<p>他抬起头，看见墙上那幅画。画里的人穿着旧时的衣裳，眉眼却和记忆里一模一样。</p>"
      + "\n".join("<p>%s</p>" % para() for _ in range(6)))
chapters.append(("第二节 画中人", c2))
toc.append(("第二节 画中人", "chapter2.xhtml", 0))

c3 = ("\n".join("<p>%s</p>" % para() for _ in range(10))
      + "\n<blockquote>“留三分余地，是给自己留退路。”——师父</blockquote>\n"
      + "<p>他记得这句话，也记得说这句话的人最后<em>并没有</em>退路。</p>"
      + "\n".join("<p>%s</p>" % para() for _ in range(6)))
chapters.append(("第三节 三分余地", c3))
toc.append(("第三节 三分余地", "chapter3.xhtml", 0))

c4 = "\n".join("<p>%s</p>" % para() for _ in range(20))
chapters.append(("第四节 旧信", c4))
toc.append(("第四节 旧信", "chapter4.xhtml", 1))  # 二级目录

c5 = "\n".join("<p>%s</p>" % para() for _ in range(10)) + "\n<p>未完待续</p>"
chapters.append(("第五节 雪落无声", c5))
toc.append(("第五节 雪落无声", "chapter5.xhtml", 1))

# 破损章节：测试容错
c6 = "\n".join("<p>%s" % para() for _ in range(8))
chapters.append(("第六节 断章", c6))
toc.append(("第六节 断章", "chapter6.xhtml", 1))

manifest_items = []
spine_items = []
for i, (title, body) in enumerate(chapters, start=1):
    path = f"Text/chapter{i}.xhtml"
    data = chapter_xhtml(title, i, body, broken=(i == 6)).encode("utf-8")
    manifest_items.append((f"ch{i}", path, "application/xhtml+xml"))
    spine_items.append(f"ch{i}")

opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>长街夜雨</dc:title>
    <dc:creator>沈砚</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId">urn:uuid:test-0001</dc:identifier>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="cover-img" href="Images/cover.png" media-type="image/png"/>
    <item id="art-img" href="Images/art.png" media-type="image/png"/>
{chr(10).join(f'    <item id="{i}" href="{h}" media-type="{t}"/>' for i, h, t in manifest_items)}
  </manifest>
  <spine toc="ncx">
{chr(10).join(f'    <itemref idref="{i}"/>' for i in spine_items)}
  </spine>
</package>
"""

navpoints = []
order = 0
for title, href, level in toc:
    order += 1
    navpoints.append(f"""    <navPoint id="np{order}" playOrder="{order}">
      <navLabel><text>{title}</text></navLabel>
      <content src="Text/{href}"/>
    </navPoint>""")

ncx = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:test-0001"/>
    <meta name="dtb:depth" content="2"/>
    <meta name="dtb:totalPageCount" content="0"/>
  </head>
  <docTitle><text>长街夜雨</text></docTitle>
  <navMap>
{chr(10).join(navpoints)}
  </navMap>
</ncx>
"""

container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
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
        z.writestr(f"OEBPS/Text/chapter{i}.xhtml",
                   chapter_xhtml(title, i, body, broken=(i == 6)),
                   compress_type=zipfile.ZIP_DEFLATED)

print("written:", OUT, os.path.getsize(OUT), "bytes")
