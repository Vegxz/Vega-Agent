#!/usr/bin/env python3
"""Generates every mipmap raster for the Vega star mark. Standard library only.

There is no image library on this machine and no build-time vector pipeline for
mipmaps, so the launcher / notification PNGs are authored here: one parametric
petal, rotated 5 x 72 degrees, flattened, scan-converted even-odd with 4x4
supersampling, and written as raw PNG chunks.

The geometry below is the SINGLE SOURCE OF TRUTH and is mirrored verbatim in
src/com/vepro/code/BrandMark.kt — change one, change the other.

  python3 tools/mklogo.py            regenerate all 25 PNGs, then verify
  python3 tools/mklogo.py --verify   re-read the PNGs on disk and check them
"""
import math
import os
import struct
import sys
import zlib

# ------------------------------------------------------------------ geometry
# 100x100 viewport, centre (50,50), +y down, tip 0 pointing up.
R_OUT = 47.0     # tip radius
R_IN = 17.0      # inner (between-petal) vertex radius
C1_R = 0.72      # outer control radius, as a fraction of R_OUT
C1_A = 9.0       # outer control angle, degrees off the tip axis
C2_R = 0.34      # inner control radius, as a fraction of R_OUT
C2_A = 28.0      # inner control angle, degrees off the tip axis
HOLE_R = 0.60    # hole tip radius, as a fraction of R_OUT
HOLE_I = 0.16    # hole inner radius, as a fraction of R_OUT
HOLE_ROT = 5.0   # hole counter-rotation, degrees
TIP0 = -90.0     # first tip points straight up

# Curve resolution and supersampling.
#
# Both raised for v6: the shape is unchanged, the sampling is not. 24 segments per
# cubic is visible as faceting on the outer curve of a claw at xxxhdpi, and 4x4
# supersampling gives 16 coverage levels per pixel, which is what made the edges of
# the star look slightly ragged rather than drawn. 64 segments puts the flattening
# error below a tenth of a pixel at every density, and 16x16 gives 256 levels — full
# 8-bit coverage, so an edge is limited by the raster grid and nothing else.
#
# Cost is (SS/4)^2 = 16x the samples of the old version, which is a few seconds for
# the whole set of 25 rasters, run once.
FLATTEN = 64     # de Casteljau segments per cubic
SS = 16          # supersampling factor per axis

# ------------------------------------------------------------------ layers
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, 'res')

DENSITIES = (('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0),
             ('xxhdpi', 3.0), ('xxxhdpi', 4.0))

# name -> (mdpi box in px, star fraction of the box, kind)
#   bg     solid white, fully opaque, no star
#   fg     black star on full transparency (adaptive foreground / themed mono)
#   legacy white ground + black star, opaque square
#   stat   white star on full transparency, no ground (notification alpha mask)
LAYERS = (
    ('ic_launcher', 48, 0.62, 'legacy'),
    ('ic_launcher_bg', 108, 0.0, 'bg'),
    ('ic_launcher_fg', 108, 58.0 / 108.0, 'fg'),
    ('ic_launcher_mono', 108, 58.0 / 108.0, 'fg'),
    ('ic_stat_vepro', 24, 0.86, 'stat'),
)


def targets():
    """[(absolute path, pixel size, star fraction, kind)] for all 25 rasters."""
    out = []
    for name, base, frac, kind in LAYERS:
        for bucket, density in DENSITIES:
            px = int(round(base * density))
            path = os.path.join(RES, 'mipmap-' + bucket, name + '.png')
            out.append((path, px, frac, kind))
    return out


# ------------------------------------------------------------------ curves
def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return (cx + r * math.cos(a), cy + r * math.sin(a))


def _lerp(p, q, t):
    return (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)


def flatten_cubic(p0, p1, p2, p3, n):
    """de Casteljau subdivision; returns the points AFTER p0, ending on p3."""
    pts = []
    for i in range(1, n + 1):
        t = float(i) / float(n)
        a = _lerp(p0, p1, t)
        b = _lerp(p1, p2, t)
        c = _lerp(p2, p3, t)
        d = _lerp(a, b, t)
        e = _lerp(b, c, t)
        pts.append(_lerp(d, e, t))
    return pts


def petals(cx, cy, s, r_out, r_in, rot):
    """One closed polygon for a whole 5-petal star, in pixel coordinates.

    s scales the 100-unit viewport to pixels; (cx, cy) is the centre in pixels.
    Both control points are pulled toward the centre (C1_R / C2_R < 1), which
    is what makes the leading edges concave and the tips taper.

    The five petals are ONE subpath (inner vertex -> tip -> inner vertex ->
    tip ... -> close), not five closed ones. Closing each petal separately
    would lay five chords across the central pentagon, and even-odd would then
    punch that pentagon out: the mark renders as a checkerboard of loose
    lobes instead of a solid star. Same curves, same constants, one contour.
    """
    pts = []
    for k in range(5):
        t = TIP0 + 72.0 * k + rot
        a = polar(cx, cy, r_in * s, t - 36.0)
        tip = polar(cx, cy, r_out * s, t)
        b = polar(cx, cy, r_in * s, t + 36.0)
        c1 = polar(cx, cy, C2_R * r_out * s, t - C2_A)
        c2 = polar(cx, cy, C1_R * r_out * s, t - C1_A)
        c3 = polar(cx, cy, C1_R * r_out * s, t + C1_A)
        c4 = polar(cx, cy, C2_R * r_out * s, t + C2_A)
        if k == 0:
            pts.append(a)
        pts.extend(flatten_cubic(a, c1, c2, tip, FLATTEN))
        pts.extend(flatten_cubic(tip, c3, c4, b, FLATTEN))
    return [pts]


TRACED = [
    [(9.49,44.08), (18.05,47.15), (25.68,50.14), (30.67,52.35), (33.67,53.92), (35.81,55.21), (37.16,56.2), (39.52,58.42), (40.73,60.34), (41.3,62.2), (41.3,64.26), (41.08,65.41), (40.51,67.05), (39.52,68.97), (38.23,70.9), (36.81,72.68), (33.74,75.82), (33.45,75.96), (33.74,75.53), (34.95,72.61), (35.59,70.61), (36.09,67.97), (36.16,65.69), (35.95,63.62), (35.09,61.13), (34.38,59.84), (33.17,58.2), (32.88,57.92), (32.6,57.92), (33.67,59.91), (34.24,61.77), (34.45,63.41), (34.45,65.69), (34.24,67.54), (33.67,70.11), (31.88,75.03), (29.67,79.6), (26.75,84.66), (23.33,89.87), (23.11,90.01), (23.11,89.8), (24.04,88.01), (25.75,83.81), (25.97,82.95), (26.32,82.31), (27.96,77.17), (28.89,73.32), (28.89,72.82), (29.1,72.47), (29.39,69.68), (29.53,69.4), (29.53,68.61), (29.67,68.33), (29.75,66.97), (29.67,64.26), (29.1,61.48), (28.53,60.06), (27.53,58.27), (26.32,56.7), (23.68,54.07), (21.4,52.14), (18.48,50), (9.49,44.15)],
    [(47.93,15.2), (47.93,18.83), (47.79,19.33), (47.79,21.33), (47.65,22.04), (47.36,27.46), (47.15,28.03), (46.79,31.46), (45.72,36.16), (44.58,39.09), (43.08,41.51), (41.51,43.22), (40.51,44.01), (38.87,45.01), (37.45,45.65), (35.45,46.22), (32.03,46.72), (28.03,46.72), (23.68,46.22), (23.4,46.08), (20.47,45.65), (20.12,45.44), (17.83,45.01), (17.48,44.79), (14.41,44.01), (7.28,41.58), (6.99,41.37), (3,39.8), (3.14,39.73), (3.36,39.87), (6.92,40.59), (9.28,40.87), (9.7,41.08), (12.77,41.51), (13.56,41.51), (13.7,41.66), (19.97,42.3), (21.97,42.37), (27.18,42.3), (27.61,42.15), (30.03,41.94), (32.95,41.23), (35.81,40.09), (38.37,38.37), (40.16,36.66), (41.94,34.31), (43.65,31.24), (45.01,27.89), (45.65,25.68), (45.79,25.54), (46.93,20.97), (47.29,18.76), (47.43,18.62), (47.93,15.27)],
    [(97,40.09), (87.51,45.36), (83.24,48.15), (79.53,50.86), (76.67,53.28), (74.11,55.99), (72.68,58.06), (71.9,59.63), (71.47,61.13), (71.32,61.27), (70.97,63.62), (70.97,66.4), (71.32,69.47), (71.47,69.68), (71.68,71.32), (72.32,74.11), (73.54,78.39), (73.89,79.17), (74.18,80.45), (75.1,82.88), (75.32,83.81), (75.53,84.09), (75.96,85.59), (76.17,85.87), (76.39,86.73), (77.32,88.94), (75.89,86.66), (74.61,84.23), (74.25,83.81), (70.83,77.53), (68.54,72.82), (66.19,67.12), (65.62,65.48), (65.33,63.69), (65.12,63.19), (65.12,60.48), (65.76,57.99), (66.4,56.56), (67.4,54.92), (68.54,53.49), (70.33,51.71), (73.11,49.57), (76.1,47.72), (80.53,45.51), (86.44,43.15), (87.51,42.87), (88.23,42.51), (91.29,41.66), (91.44,41.51), (96.93,40.16)],
    [(50.43,9.28), (50.57,9.42), (50.57,9.99), (51.78,15.48), (52,15.77), (52.21,17.05), (52.64,18.26), (52.85,19.4), (54.64,25.11), (55.85,28.32), (56.99,30.89), (59.13,34.67), (61.05,37.16), (62.84,38.87), (64.55,40.09), (66.9,41.23), (68.97,41.87), (71.75,42.3), (76.67,42.3), (77.17,42.15), (78.31,42.15), (81.95,41.8), (83.02,41.66), (83.24,41.51), (84.66,41.44), (85.02,41.3), (87.23,41.08), (87.66,40.87), (88.73,40.8), (88.51,41.01), (87.02,41.37), (84.66,42.23), (78.53,44.01), (75.39,44.79), (74.96,44.79), (74.61,45.01), (69.9,45.86), (66.12,46.01), (63.84,45.72), (61.98,45.01), (59.63,43.44), (58.56,42.44), (56.92,40.51), (55.14,37.73), (53.49,34.31), (52.42,31.24), (51.78,28.68), (51.43,26.54), (51.28,26.39), (51.07,24.11), (50.64,21.61), (50.43,16.98), (50.29,16.27), (50.29,9.85), (50.43,9.35)],
    [(71.18,48.79), (71.32,48.79), (70.97,49.14), (69.47,50.21), (68.26,51.28), (67.05,52.5), (65.69,54.14), (64.26,56.49), (63.69,57.92), (63.41,59.41), (63.27,59.63), (63.27,62.34), (63.41,62.55), (63.76,64.55), (64.48,66.76), (67.26,73.46), (70.47,79.88), (66.62,76.25), (61.77,72.39), (58.06,70.11), (54.07,68.33), (51.14,67.62), (46.86,67.62), (45.65,67.83), (45.51,67.97), (45.15,67.97), (44.15,68.33), (42.65,68.97), (40.51,70.25), (40.44,70.18), (43.44,67.05), (45.29,65.62), (46.15,65.12), (47.65,64.41), (49.36,63.91), (52.71,63.84), (54.92,64.41), (56.35,65.05), (58.06,66.05), (61.27,68.47), (63.62,70.68), (63.69,70.47), (62.41,68.83), (60.34,65.55), (59.34,63.19), (59.13,62.05), (59.13,60.06), (59.34,58.99), (59.77,57.85), (60.48,56.56), (61.41,55.35), (63.19,53.57), (66.62,51.14), (71.11,48.86)],
    [(48.57,69.19), (50.43,69.26), (51.71,69.47), (54.78,70.47), (56.7,71.32), (58.49,72.32), (61.91,74.61), (64.12,76.32), (66.83,78.67), (70.33,82.17), (74.53,87.02), (77.17,90.37), (77.32,90.72), (75.46,89.08), (69.76,84.52), (65.76,81.67), (61.7,79.1), (59.13,77.81), (58.92,77.6), (57.99,77.24), (57.77,77.03), (55.85,76.25), (53.14,75.39), (52.78,75.39), (52.64,75.25), (51.21,75.03), (48.93,74.96), (46.36,75.25), (44.44,75.82), (41.51,77.03), (38.16,78.88), (35.09,80.88), (30.46,84.31), (26.04,87.8), (29.32,83.52), (33.17,78.96), (37.23,74.75), (39.3,72.97), (41.58,71.32), (43.94,70.11), (45.86,69.47), (47.15,69.26), (48.5,69.26)],
    [(49.36,26.82), (49.71,27.75), (50.57,31.24), (52.35,36.02), (54.49,40.09), (56.35,42.65), (57.7,44.08), (59.06,45.22), (59.98,45.86), (62.12,46.93), (63.91,47.5), (65.26,47.72), (67.47,47.79), (66.97,48), (65.62,48.07), (65.33,48.22), (63.19,48.29), (61.41,48.22), (60.06,48), (58.2,47.43), (56.2,46.43), (55.28,45.79), (53.49,44.01), (52.5,42.58), (51.28,40.16), (50.14,36.45), (49.86,34.1), (49.64,33.6), (49.64,32.95), (49.5,32.67), (49.36,26.89)],
    [(46.01,39.52), (45.72,41.01), (45.36,41.73), (45.15,42.65), (43.94,45.15), (42.73,46.79), (42.08,47.43), (40.37,48.64), (39.3,49.14), (37.73,49.5), (37.59,49.64), (35.24,49.79), (33.53,49.64), (33.1,49.43), (31.17,49.14), (28.6,48.36), (30.74,48.43), (35.17,48.07), (35.38,47.93), (35.81,47.93), (37.45,47.5), (39.73,46.51), (41.73,45.15), (43.44,43.51), (45.29,40.94), (45.93,39.59)],
]


def star_polys(cx, cy, size):
    """The whole mark, as traced polygons scaled into the requested box.

    TRACED is the artwork's real outline (see BrandMark.kt for how it was
    obtained). The parametric petal generator that used to live here produced a
    chunky star sharing only ~29% of its area with the real logo; the traced
    outline matches it to IoU 0.98. Contours do not overlap, so a NON-ZERO fill
    is correct and the gaps between the claws are simply background.
    """
    s = size / 100.0
    out = []
    for poly in TRACED:
        out.append([(cx - size / 2.0 + x * s, cy - size / 2.0 + y * s) for (x, y) in poly])
    return out


def polys_bbox(polys):
    xs = [p[0] for poly in polys for p in poly]
    ys = [p[1] for poly in polys for p in poly]
    return (min(xs), min(ys), max(xs), max(ys))


# ------------------------------------------------------------------ raster
def rasterize(polys, w, h, ss=SS):
    """Even-odd scan conversion with ss x ss supersampling -> alpha bytearray.

    Three rules keep the fill watertight:
      * every sub-scanline is offset by +0.5/ss, so it can never land exactly
        on a vertex y (which would double- or zero-count the crossing);
      * horizontal edges are skipped outright;
      * the half-open rule min(y0,y1) <= y < max(y0,y1) counts a shared vertex
        exactly once.
    Break any one of them and a white gash opens through the star.
    """
    edges = []
    for poly in polys:
        n = len(poly)
        for i in range(n):
            x0, y0 = poly[i]
            x1, y1 = poly[(i + 1) % n]
            if y0 == y1:
                continue                       # horizontal edge: skip
            edges.append((min(y0, y1), max(y0, y1), x0, y0,
                          (x1 - x0) / (y1 - y0)))

    buckets = [[] for _ in range(h)]
    for e in edges:
        lo = max(0, int(math.floor(e[0])))
        hi = min(h - 1, int(math.ceil(e[1])))
        for row in range(lo, hi + 1):
            buckets[row].append(e)

    alpha = bytearray(w * h)
    sub_w = w * ss
    total = ss * ss
    for py in range(h):
        active = buckets[py]
        if not active:
            continue
        acc = [0] * w
        for sub in range(ss):
            sy = py + (sub + 0.5) / ss        # never an integer, never a vertex
            xs = []
            for ymin, ymax, x0, y0, inv in active:
                if ymin <= sy < ymax:         # half-open
                    xs.append(x0 + (sy - y0) * inv)
            if len(xs) < 2:
                continue
            xs.sort()
            row = bytearray(sub_w)
            for i in range(0, len(xs) - 1, 2):
                ja = int(math.ceil(xs[i] * ss - 0.5))
                jb = int(math.ceil(xs[i + 1] * ss - 0.5))
                if ja < 0:
                    ja = 0
                if jb > sub_w:
                    jb = sub_w
                if jb > ja:
                    row[ja:jb] = b'\x01' * (jb - ja)
            for px in range(w):
                cell = row[px * ss:(px + 1) * ss]
                if cell.count(1):
                    acc[px] += cell.count(1)
        base = py * w
        for px in range(w):
            v = acc[px]
            if v:
                alpha[base + px] = (v * 255 + total // 2) // total
    return alpha


def layer_pixels(size, frac, kind):
    """Raw non-premultiplied samples plus the PNG colour type for one layer."""
    if kind == 'bg':
        return bytearray(b'\xff' * (size * size * 3)), 2
    alpha = rasterize(star_polys(size / 2.0, size / 2.0, size * frac),
                      size, size)
    if kind == 'legacy':
        px = bytearray(size * size * 3)
        for i in range(size * size):
            v = 255 - alpha[i]                 # white ground, black star
            px[i * 3] = v
            px[i * 3 + 1] = v
            px[i * 3 + 2] = v
        return px, 2
    px = bytearray(size * size * 4)
    lum = 255 if kind == 'stat' else 0
    for i in range(size * size):
        a = alpha[i]
        if a:
            px[i * 4] = lum
            px[i * 4 + 1] = lum
            px[i * 4 + 2] = lum
            px[i * 4 + 3] = a
    return px, 6


# ------------------------------------------------------------------ png i/o
def _chunk(tag, data):
    body = tag + data
    return struct.pack('>I', len(data)) + body + struct.pack('>I',
                                                             zlib.crc32(body) & 0xFFFFFFFF)


def write_png(path, w, h, pixels, color_type):
    bpp = 4 if color_type == 6 else 3
    stride = w * bpp
    raw = bytearray()
    for y in range(h):
        raw.append(0)                          # filter type 0 (None)
        raw += pixels[y * stride:(y + 1) * stride]
    out = bytearray(b'\x89PNG\r\n\x1a\n')
    out += _chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, color_type, 0, 0, 0))
    out += _chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    out += _chunk(b'IEND', b'')
    with open(path, 'wb') as fh:
        fh.write(bytes(out))


def read_png(path):
    """Minimal decoder -> (w, h, colour type, unfiltered sample bytes)."""
    with open(path, 'rb') as fh:
        blob = fh.read()
    if blob[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('%s: not a PNG' % path)
    pos = 8
    w = h = color_type = 0
    idat = bytearray()
    while pos < len(blob):
        length = struct.unpack('>I', blob[pos:pos + 4])[0]
        tag = blob[pos + 4:pos + 8]
        data = blob[pos + 8:pos + 8 + length]
        want = struct.unpack('>I', blob[pos + 8 + length:pos + 12 + length])[0]
        got = zlib.crc32(tag + data) & 0xFFFFFFFF
        if want != got:
            raise ValueError('%s: bad CRC on %s' % (path, tag))
        if tag == b'IHDR':
            w, h, depth, color_type = struct.unpack('>IIBB', data[:10])
            if depth != 8 or color_type not in (2, 6):
                raise ValueError('%s: unexpected depth/colour type' % path)
        elif tag == b'IDAT':
            idat += data
        elif tag == b'IEND':
            break
        pos += 12 + length
    bpp = 4 if color_type == 6 else 3
    raw = zlib.decompress(bytes(idat))
    stride = w * bpp
    if len(raw) != (stride + 1) * h:
        raise ValueError('%s: truncated IDAT' % path)
    out = bytearray(stride * h)
    prev = bytearray(stride)
    for y in range(h):
        ft = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ft == 1:
                line[i] = (line[i] + a) & 0xFF
            elif ft == 2:
                line[i] = (line[i] + b) & 0xFF
            elif ft == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif ft == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
            elif ft != 0:
                raise ValueError('%s: unknown filter %d' % (path, ft))
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, color_type, out


# ------------------------------------------------------------------ verify
class Fail(Exception):
    pass


def _need(cond, msg):
    if not cond:
        raise Fail(msg)


def _alpha_of(px, color_type, w, h):
    if color_type == 2:
        # opaque layer: treat "ink" as any non-white pixel
        return bytearray(255 - px[i * 3] for i in range(w * h))
    return bytearray(px[i * 4 + 3] for i in range(w * h))


def verify_one(path, size, frac, kind):
    name = os.path.basename(path)
    bucket = os.path.basename(os.path.dirname(path))
    tag = bucket + '/' + name
    w, h, ct, px = read_png(path)
    _need(w == size and h == size,
          '%s: expected %dx%d, got %dx%d' % (tag, size, size, w, h))

    if kind == 'bg':
        _need(ct == 2, '%s: background must be opaque RGB (colour type 2)' % tag)
        _need(px.count(255) == len(px),
              '%s: background must be solid #FFFFFF with no star' % tag)
        return

    if kind == 'legacy':
        _need(ct == 2, '%s: legacy icon must be opaque RGB (colour type 2)' % tag)
        _need(px[0] == 255 and px[1] == 255 and px[2] == 255,
              '%s: legacy icon must have a white ground' % tag)
        _need(min(px) <= 8, '%s: legacy icon has no black star' % tag)
    else:
        _need(ct == 6, '%s: must be RGBA (colour type 6)' % tag)
        lum = 255 if kind == 'stat' else 0
        corners = (0, w - 1, (h - 1) * w, h * w - 1)
        for c in corners:
            _need(px[c * 4 + 3] == 0, '%s: corner is not transparent' % tag)
        ink = 0
        for i in range(w * h):
            a = px[i * 4 + 3]
            if a == 0:
                continue
            ink += 1
            _need(px[i * 4] == lum and px[i * 4 + 1] == lum
                  and px[i * 4 + 2] == lum,
                  '%s: star pixels must be %s only' % (
                      tag, 'white' if lum else 'black'))
        _need(0 < ink < w * h,
              '%s: layer must be a star on transparency, not a filled block' % tag)
        # The centre of the mark is the innermost hole: it must stay
        # transparent, or a themed / notification icon renders as a blob.
        # (At 24px the centre pixel straddles the hole's edge, hence the
        # threshold rather than a hard zero — a filled hole reads 255.)
        mid = (h // 2) * w + (w // 2)
        _need(px[mid * 4 + 3] < 96,
              '%s: the inner hole is filled — it must be transparent' % tag)

    alpha = _alpha_of(px, ct, w, h)
    rows = [y for y in range(h) if max(alpha[y * w:(y + 1) * w]) > 8]
    cols_hit = [False] * w
    for y in rows:
        row = alpha[y * w:(y + 1) * w]
        for x in range(w):
            if row[x] > 8:
                cols_hit[x] = True
    cols = [x for x in range(w) if cols_hit[x]]
    _need(rows and cols, '%s: nothing was drawn' % tag)
    _need(rows[-1] - rows[0] + 1 == len(rows),
          '%s: an empty scanline runs through the star (fill rule broken)' % tag)

    exp = polys_bbox(star_polys(size / 2.0, size / 2.0, size * frac))
    tol = 1.6
    _need(abs(cols[0] - exp[0]) <= tol and abs(rows[0] - exp[1]) <= tol
          and abs(cols[-1] + 1 - exp[2]) <= tol
          and abs(rows[-1] + 1 - exp[3]) <= tol,
          '%s: star bounds %s do not match the %.4f-of-box geometry %s' % (
              tag, (cols[0], rows[0], cols[-1] + 1, rows[-1] + 1),
              frac, tuple(round(v, 2) for v in exp)))

    # The mark is TRACED artwork, not a generated polygon, so the old 5-fold
    # rotational-symmetry assertion no longer describes it: the source logo is
    # hand-drawn and its five claws differ by a pixel or two. Asserting machine
    # symmetry would force us to distort the very shape we were asked to
    # reproduce exactly.
    #
    # The invariant that actually matters now is FIDELITY: does the raster on
    # disk match the traced outline it was generated from? That is checked by
    # re-rasterising the same polygons at this size and comparing coverage.
    ref = rasterize(star_polys(size / 2.0, size / 2.0, size * frac), size, size)
    same = 0
    total = 0
    for i in range(0, len(alpha)):
        want = 255 if ref[i] else 0
        got = alpha[i]
        # Anti-aliased edge pixels are free to differ; interiors are not.
        if got in (0, 255) and want in (0, 255):
            total += 1
            if (got > 127) == (want > 127):
                same += 1
    _need(total > size * size // 4,
          '%s: fidelity check found too few decidable pixels' % tag)
    ratio = same / float(total)
    _need(ratio > 0.985,
          '%s: raster differs from the traced outline (%.4f of solid pixels agree)'
          % (tag, ratio))


def verify():
    bad = 0
    for path, size, frac, kind in targets():
        try:
            verify_one(path, size, frac, kind)
            print('ok   %s (%dx%d)' % (
                os.path.relpath(path, ROOT), size, size))
        except (Fail, ValueError) as exc:
            bad += 1
            print('FAIL %s' % exc)
    if bad:
        print('%d of 25 rasters failed verification' % bad)
        return 1
    print('all 25 rasters verified: geometry, layer semantics, PNG structure')
    return 0


def generate():
    for path, size, frac, kind in targets():
        d = os.path.dirname(path)
        if not os.path.isdir(d):
            os.makedirs(d)
        px, ct = layer_pixels(size, frac, kind)
        write_png(path, size, size, px, ct)
        print('wrote %s (%dx%d, colour type %d)' % (
            os.path.relpath(path, ROOT), size, size, ct))


def main(argv):
    if '--verify' in argv:
        return verify()
    generate()
    return verify()


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
