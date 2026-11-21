#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 PNG 裁剪/放大工具（不依赖 Pillow）：用于放大查看设备截图细节。"""
import sys, zlib, struct

def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    idat = b""
    w = h = bit = ctype = 0
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b"IHDR":
            w, h, bit, ctype, comp, filt, interlace = struct.unpack(">IIBBBBB", chunk)
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        pos += 12 + ln
    assert bit == 8 and interlace == 0, (bit, interlace)
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ctype]
    raw = zlib.decompress(idat)
    stride = w * ch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i-ch]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]
                c = prev[i-ch] if i >= ch else 0
                pp = a + b - c
                pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, out

def write_png(path, w, h, ch, pix):
    ctype = {1: 0, 3: 2, 4: 6}[ch]
    raw = bytearray()
    stride = w * ch
    for y in range(h):
        raw.append(0)
        raw.extend(pix[y*stride:(y+1)*stride])
    def chunk(typ, d):
        return struct.pack(">I", len(d)) + typ + d + struct.pack(">I", zlib.crc32(typ + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")
    open(path, "wb").write(png)

def crop_scale(src, dst, x, y, cw, chh, scale=2):
    w, h, ch, pix = read_png(src)
    x = max(0, min(x, w-1)); y = max(0, min(y, h-1))
    cw = min(cw, w-x); chh = min(chh, h-y)
    ow, oh = cw*scale, chh*scale
    out = bytearray(ow*oh*ch)
    for oy in range(oh):
        sy = y + oy // scale
        for ox in range(ow):
            sx = x + ox // scale
            si = (sy*w + sx)*ch
            di = (oy*ow + ox)*ch
            out[di:di+ch] = pix[si:si+ch]
    write_png(dst, ow, oh, ch, out)
    print(f"cropped {cw}x{chh} -> {ow}x{oh}")

if __name__ == "__main__":
    a = sys.argv[1:]
    src, dst = a[0], a[1]
    x, y, cw, chh = (int(v) for v in a[2:6])
    scale = int(a[6]) if len(a) > 6 else 2
    crop_scale(src, dst, x, y, cw, chh, scale)
