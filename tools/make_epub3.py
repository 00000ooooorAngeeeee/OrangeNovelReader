#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EPUB3 测试：nav.xhtml 目录（含嵌套）、无 NCX、无封面、章节带 BOM、章节在包根目录。"""
import os, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "testdata", "epub3.epub"))

chapters = [
    ("序章", "这个故事开始于一场没有预报的雪。", "c0.xhtml", 0),
    ("第一章 雪夜", "路上的雪积了半尺，脚印很快就被掩住。", "c1.xhtml", 0),
    ("第二章 旧事", "他记得那年的雪也这样大，只是身边还站着一个人。", "c2.xhtml", 1),
    ("第三章 门", "门是从里面锁上的。", "c3.xhtml", 1),
]

def body(title, lead):
    filler = ("他把手拢在袖子里，一步步往前走。街边的灯一盏盏亮起来，"
              "映在雪上成了晕开的一团暖色。") * 6
    return f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head>
<body>
<section epub:type="chapter">
<h1>{title}</h1>
<p>{lead}</p>
<p>{filler}</p>
<p>{filler}</p>
</section>
</body>
</html>"""

nl = "\n"
nav_items = []
for title, lead, href, level in chapters:
    if level == 0:
        nav_items.append(f'<li><a href="{href}">{title}</a></li>')
    else:
        nav_items.append(f'<li><a href="{href}">{title}</a></li>')

nav = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head>
<body>
<nav epub:type="toc" id="toc">
<h1>目录</h1>
<ol>
<li><a href="c0.xhtml">序章</a></li>
<li><a href="c1.xhtml">第一章 雪夜</a>
  <ol>
  <li><a href="c2.xhtml">第二章 旧事</a></li>
  <li><a href="c3.xhtml">第三章 门</a></li>
  </ol>
</li>
</ol>
</nav>
<nav epub:type="landmarks" hidden="hidden">
<ol><li><a epub:type="bodymatter" href="c0.xhtml">正文</a></li></ol>
</nav>
</body>
</html>
"""

opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:epub3-test</dc:identifier>
    <dc:title>雪夜行（EPUB3 测试）</dc:title>
    <dc:creator>佚名</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c0"/>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="c3"/>
  </spine>
</package>
"""

container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

with zipfile.ZipFile(OUT, "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/package.opf", opf, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/nav.xhtml", nav, compress_type=zipfile.ZIP_DEFLATED)
    for title, lead, href, level in chapters:
        data = body(title, lead)
        if href == "c0.xhtml":
            data = "\ufeff" + data  # 故意加 BOM
        z.writestr(f"OEBPS/{href}", data, compress_type=zipfile.ZIP_DEFLATED)

print("written:", OUT, os.path.getsize(OUT))
