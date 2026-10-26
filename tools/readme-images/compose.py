"""Frames the README's captures (app/src/test/.../readme, CONTRIBUTING: README's images) for the README.

    python3 -I tools/readme-images/compose.py CAPTURES OUT

CAPTURES holds what the capture test wrote: stills/<name>.png at 1920 x 1080, and frames/NNNN.png, a frame every
32 ms, with frames/cues.txt, the frame each step of the animation starts on. OUT receives:

- hero.webp, home on a TV on the wall of a dark room lit by its own picture (bias lighting), 2400 px wide, and
  hero-dark.webp, the same on a wall of GitHub's dark theme, into which its edges melt;
- social-preview.jpg, GitHub's link preview, 1280 x 640;
- a card per screen (film.webp, show.webp...): rounded, a hairline at its edge and a soft shadow on a transparent
  ground, so it reads on GitHub's light and dark themes; film and library the README's width, the others half of it;
- tour.webp, the animation, framed as a card the README's width, looping on home as the lights come up.

Cards are measured in the README's own pixels, so that their corners, hairlines and shadows look alike whatever
their size there. Pillow only, no text drawn: captions stay in the README. Run with Pillow 11
(tools/readme-images/requirements.txt).
"""

import argparse
import functools
import re
import sys
import warnings
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, features

# The room: pure black, as the app's own (DESIGN.md, Colour); and the page of GitHub's dark theme.
ROOM = (0, 0, 0)
GITHUB_DARK = (13, 17, 23)

# Supersampling for the curves: masks are drawn at this scale, then reduced.
SS = 4

# The README's column on GitHub, in CSS pixels, and the share of it a card takes: all of it, or one of a pair.
README_PX = 880
WIDE, HALF = 1.0, 0.49

# The screens the README shows, framed as cards, by the still each comes from: the screen's width in the card, the
# share of the column it takes, and the part of the TV kept, 16:9 still, so that no row shows as a sliver at its foot.
# The captures hold more (to-identify, identify, update) for whoever needs them.
CARDS = {
    "film": (1760, WIDE, (0, 0, 1760, 990)),
    "show": (1200, HALF, None),
    "saga": (1200, HALF, (0, 0, 1707, 960)),
    "library": (1760, WIDE, (62, 0, 1858, 1010)),
    "watch": (1200, HALF, (0, 0, 1776, 999)),
    "settings": (1200, HALF, None),
}

# A card, in the README's pixels: its corners, its hairline, and its two shadows (blur, drop, opacity), a wide soft
# one and a tight one, as light falls on a thing a little above its ground; its margin holds the wide one whole.
RADIUS = 8
HAIRLINE = 0.6
SHADOWS = ((4.5, 2.5, 0.30), (1.5, 0.8, 0.22))
MARGIN = 12.5

# The animation: the screen's width in its card, the README's width; a frame every 32 ms (cues.txt says it).
TOUR_WIDTH = 1200

# Where « To watch » opens, its hero settles on the title in focus (rememberSettled, then its backdrop's fade): the
# animation cuts to it through a fade of FADE frames from home rather than show the empty header meanwhile.
SETTLE_MS = 420
FADE = 6

# Between the dim and the lights coming up, this many black frames on each side, with the cue's darkest steps about
# 0.25 s of black: black longer reads as an image that failed to load. A frame is black when no pixel is above
# BLACK_LEVEL.
BLACK_FRAMES = 2
BLACK_LEVEL = 6

# The light on the wall: the picture's colours a little richer, the far light warmed toward a neutral glow rather than
# left to mix into a muddy tone, computed at a quarter of the size, then dithered so that no contour shows.
SATURATION = 1.15
WARM = (255, 170, 120)
WARMTH = 0.35
GLOW_STEP = 4
DITHER = 0.7

TITLE_CARD = Path(__file__).resolve().parents[2] / "docs" / "images" / "houselights.svg"


def rounded_mask(size, radius):
    """An antialiased mask of a rectangle of [size] with corners of [radius]."""
    w, h = size
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], round(radius * SS), fill=255)
    return big.resize(size, Image.LANCZOS)


def ring_mask(size, radius, width):
    """An antialiased mask of a hairline [width] wide just inside a rounded rectangle's edge."""
    w, h = size
    big = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(big)
    d.rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], round(radius * SS), fill=255)
    inset = round(width * SS)
    inner = max(round(radius * SS) - inset, 0)
    d.rounded_rectangle([inset, inset, w * SS - 1 - inset, h * SS - 1 - inset], inner, fill=0)
    return big.resize(size, Image.LANCZOS)


def scaled(im, width):
    """[im] at [width], its height kept in ratio, reduced with Lanczos."""
    height = round(im.height * width / im.width)
    return im.resize((width, height), Image.LANCZOS)


@functools.lru_cache(maxsize=4)
def chrome(size, share):
    """What every card of a screen of [size] taking [share] of the column has: its margin, its ground with the
    shadows, its face's mask and its hairline; made once for all the animation's frames."""
    w, h = size
    # Image pixels to the README's: the card, margins included, takes the share of the column.
    px = w / (README_PX * share - 2 * MARGIN)
    pad = round(MARGIN * px)
    radius = RADIUS * px
    canvas = (w + 2 * pad, h + 2 * pad)
    ground = Image.new("RGBA", canvas, (0, 0, 0, 0))
    face = rounded_mask((w, h), radius)
    for blur, drop, opacity in SHADOWS:
        shade = Image.new("L", canvas, 0)
        shade.paste(face.point(lambda v, o=opacity: round(v * o)), (pad, pad + round(drop * px)))
        shade = shade.filter(ImageFilter.GaussianBlur(blur * px))
        ground.alpha_composite(Image.merge("RGBA", (*[Image.new("L", canvas, 0)] * 3, shade)))
    # The hairline: projector white at 14 % over the picture's edge, which a black screen on a dark page needs.
    edge = Image.new("RGBA", (w, h), (244, 244, 245, 255))
    edge.putalpha(ring_mask((w, h), radius, max(HAIRLINE * px, 1.0)).point(lambda v: round(v * 0.14)))
    return pad, ground, face, edge


def card(shot, width, share=HALF, crop=None):
    """A screen as a card: [crop] of it at [width], rounded, a hairline at its edge, soft shadows beneath, on a
    transparent ground; [share] of the README's column."""
    shot = shot.convert("RGB")
    if crop:
        shot = shot.crop(crop)
    screen = scaled(shot, width)
    pad, ground, mask, edge = chrome(screen.size, share)
    canvas = ground.copy()
    face = screen.convert("RGBA")
    face.putalpha(mask)
    face.alpha_composite(edge)
    canvas.alpha_composite(face, (pad, pad))
    return canvas


def luma(colour):
    return 0.299 * colour[0] + 0.587 * colour[1] + 0.114 * colour[2]


def tinted(im, colour):
    """[im] in [colour]'s hue, each pixel as bright as it was."""
    grey = im.convert("L")
    y = luma(colour)
    return Image.merge("RGB", [grey.point(lambda v, c=c: min(255, round(v * c / y))) for c in colour])


def glow(screen, size, at, spread, blur, strength, reach=(0.62, 0.70), warmth=0.0):
    """[screen]'s colours spread [spread] times its size around it, blurred by [blur] of its width, faded by [reach],
    [warmth] of the way to a warm neutral."""
    w, h = size
    sw, sh = screen.size
    q = GLOW_STEP
    small = ImageEnhance.Color(screen.resize((64, 36), Image.BOX)).enhance(SATURATION)
    if warmth:
        small = Image.blend(small, tinted(small, WARM), warmth)
    gw, gh = round(sw * spread / q), round(sh * spread / q)
    pad = max(gw, gh)
    room = Image.new("RGB", (w // q + 2 * pad, h // q + 2 * pad), ROOM)
    cx, cy = (at[0] + sw / 2) / q + pad, (at[1] + sh / 2) / q + pad
    room.paste(small.resize((gw, gh), Image.BILINEAR), (round(cx - gw / 2), round(cy - gh / 2)))
    room = room.filter(ImageFilter.GaussianBlur(sw / q * blur))
    room = room.crop((pad, pad, pad + w // q, pad + h // q)).resize((w, h), Image.BICUBIC)
    falloff = Image.new("L", (w // q, h // q), 0)
    ex, ey = sw * spread * reach[0] / q, sh * spread * reach[1] / q
    cx, cy = cx - pad, cy - pad
    ImageDraw.Draw(falloff).ellipse([cx - ex, cy - ey, cx + ex, cy + ey], fill=255)
    falloff = falloff.filter(ImageFilter.GaussianBlur(sw / q * blur * 1.4)).resize((w, h), Image.BICUBIC)
    return Image.composite(room, Image.new("RGB", (w, h), ROOM), falloff.point(lambda v: round(v * strength)))


def bias_light(screen, size, at):
    """The light a TV's back throws on the wall: its picture's own colours, bright close to the set, fading far off,
    to nothing at the image's edges."""
    screen = screen.convert("RGB")
    near = glow(screen, size, at, spread=1.16, blur=0.05, strength=0.55)
    far = glow(screen, size, at, spread=1.75, blur=0.14, strength=0.42, reach=(0.6, 0.66), warmth=WARMTH)
    light = ImageChops.screen(near, far)
    w, h = size
    inset = round(min(w, h) * 0.06)
    edges = Image.new("L", size, 0)
    ImageDraw.Draw(edges).rectangle([inset, inset, w - 1 - inset, h - 1 - inset], fill=255)
    edges = edges.filter(ImageFilter.GaussianBlur(inset / 2))
    return Image.composite(light, Image.new("RGB", size, ROOM), edges)


def dithered(light):
    """[light] with a little noise where it glows, so that its gradients show no contour once quantised."""
    # The difference of two of Pillow's noises, whose own rounds down: centred on 128, DITHER levels across.
    one, two = (Image.effect_noise(light.size, DITHER / 1.75) for _ in range(2))
    noise = ImageChops.subtract(one, two, 1.0, 128)
    flat = Image.new("L", light.size, 128)
    lit = light.convert("L").point(lambda v: min(255, v * 64))
    noise = Image.composite(noise, flat, lit)
    return ImageChops.add(light, Image.merge("RGB", (noise, noise, noise)), 1.0, -128)


def tv(shot, width=2400, height=1350, screen_width=1800, top=160, wall=ROOM):
    """[shot] on a TV hung on a wall of [wall], in a dark room lit by its own picture, a thin bezel around it."""
    screen = scaled(shot.convert("RGB"), screen_width)
    sw, sh = screen.size
    x = (width - sw) // 2
    light = dithered(bias_light(screen, (width, height), (x, top)))
    room = ImageChops.screen(light, Image.new("RGB", light.size, wall)).convert("RGBA")
    # The set: a thin black bezel, its outer edge catching a little of the room's light; then the picture.
    bezel = round(sw * 0.0055)
    body = (sw + 2 * bezel, sh + 2 * bezel)
    set_layer = Image.new("RGBA", body, (10, 10, 12, 255))
    set_layer.putalpha(rounded_mask(body, bezel + 4))
    rim = Image.new("RGBA", body, (120, 120, 132, 255))
    rim.putalpha(ring_mask(body, bezel + 4, 1.2).point(lambda v: round(v * 0.45)))
    set_layer.alpha_composite(rim)
    face = screen.convert("RGBA")
    face.putalpha(rounded_mask((sw, sh), 3))
    set_layer.alpha_composite(face, (bezel, bezel))
    room.alpha_composite(set_layer, (x - bezel, top - bezel))
    return room


def hero(shot, wall=ROOM):
    """Home on the TV, 2400 px wide, the room's corners rounded off the page."""
    room = tv(shot, wall=wall)
    room.putalpha(rounded_mask(room.size, 28))
    return room


def social(shot):
    """GitHub's link preview, 1280 x 640: the TV in its room, the lockup above it."""
    room = tv(shot, width=1280, height=640, screen_width=820, top=128).convert("RGB")
    lockup = title_lockup(height=30)
    if lockup is not None:
        room.paste(lockup, ((room.width - lockup.width) // 2, 52), lockup)
    return room


def title_lockup(height):
    """The lockup, Gradation in its light beside the name, cut from the README's title card, [height] tall."""
    if not TITLE_CARD.exists():
        return None
    svg = TITLE_CARD.read_text()
    # The card is 1280 x 480; its paths are in a group scaled by 2.66667 around the lockup, at 320 x 180 units.
    scale = 4
    unit = 2.66667 * scale
    paths = re.findall(r'<path fill="([^"]+)" d="([^"]+)"/>', svg)
    marks, names = [], []
    for fill, d in paths:
        if d.startswith("M0 0h320"):
            continue
        (marks if fill.startswith("url") else names).append(d)
    w, h = round(320 * unit), round(180 * unit)
    mark = Image.new("L", (w, h), 0)
    name = Image.new("L", (w, h), 0)
    for d in marks:
        draw_path(mark, d, unit)
    for d in names:
        draw_path(name, d, unit)
    # The mark above the name on the card: set beside it as the app's lockup, 1.4 times the name's capitals.
    mark_box, name_box = mark.getbbox(), name.getbbox()
    mark_im, name_im = mark.crop(mark_box), name.crop(name_box)
    caps = name_im.height
    mark_im = mark_im.resize((round(mark_im.width * caps * 1.4 / mark_im.height), round(caps * 1.4)), Image.LANCZOS)
    # The name the wide bar's width after the mark (Gradation.lockupGap, DESIGN.md, Brand).
    gap = round(caps * 0.622)
    lw, lh = mark_im.width + gap + name_im.width, mark_im.height
    alpha = Image.new("L", (lw, lh), 0)
    alpha.paste(mark_im, (0, 0))
    alpha.paste(name_im, (mark_im.width + gap, (lh - caps) // 2))
    colour = Image.new("RGB", (lw, lh), (244, 244, 245))
    # One gradient across the bars, ember's orange at the wide bar's left to its raspberry at the thin bar's right.
    ramp = Image.linear_gradient("L").rotate(90).resize((mark_im.width, lh))
    ends = zip((255, 159, 28), (255, 46, 99))
    light = Image.merge("RGB", [ramp.point(lambda v, a=a, b=b: round(a + (b - a) * v / 255)) for a, b in ends])
    colour.paste(light, (0, 0))
    lockup = colour.convert("RGBA")
    lockup.putalpha(alpha)
    target = round(height * 1.4)
    return lockup.resize((round(lw * target / lh), target), Image.LANCZOS)


def draw_path(mask, d, unit):
    """Fills SVG path [d] (M, L, H, V, Q, Z, absolute or relative) into [mask], even-odd, at [unit] pixels a unit."""
    tokens = re.findall(r"[MLHVQZmlhvqz]|-?\d*\.?\d+", d)
    subpaths, points, i, cmd = [], [], 0, "M"
    x = y = 0.0
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if points:
                    subpaths.append(points)
                    x, y = points[0]
                points = []
            continue
        relative = cmd.islower()
        ox, oy = (x, y) if relative else (0.0, 0.0)
        op = cmd.upper()
        if op in "ML":
            x, y = ox + float(tokens[i]), oy + float(tokens[i + 1])
            if op == "M":
                if points:
                    subpaths.append(points)
                points = [(x, y)]
                cmd = "l" if relative else "L"
            else:
                points.append((x, y))
            i += 2
        elif op == "H":
            x = ox + float(tokens[i])
            points.append((x, y))
            i += 1
        elif op == "V":
            y = oy + float(tokens[i])
            points.append((x, y))
            i += 1
        elif op == "Q":
            qx, qy = ox + float(tokens[i]), oy + float(tokens[i + 1])
            ex, ey = ox + float(tokens[i + 2]), oy + float(tokens[i + 3])
            for step in range(1, 9):
                t = step / 8
                a, b, c = (1 - t) ** 2, 2 * (1 - t) * t, t * t
                points.append((a * x + b * qx + c * ex, a * y + b * qy + c * ey))
            x, y = ex, ey
            i += 4
        else:
            i += 1
    if points:
        subpaths.append(points)
    for sub in subpaths:
        layer = Image.new("L", mask.size, 0)
        ImageDraw.Draw(layer).polygon([(px * unit, py * unit) for px, py in sub], fill=255)
        mask.paste(ImageChops.logical_xor(mask.convert("1"), layer.convert("1")).convert("L"))


def read_cues(frames):
    """The frame each step of the animation starts on, by name, and the frames' interval: frames/cues.txt."""
    path = frames / "cues.txt"
    if not path.exists():
        sys.exit(f"No {path}: the frames were filmed without their cues")
    lines = (line.split() for line in path.read_text().splitlines() if line.strip())
    cues = {name: int(at) for name, at in lines}
    missing = {"frame-ms", "lit", "watch", "page", "play"} - cues.keys()
    if missing:
        sys.exit(f"{path} names no {', '.join(sorted(missing))}")
    return cues


def black(path):
    """Whether the frame at [path] is all black."""
    with Image.open(path) as im:
        return im.convert("L").getextrema()[1] <= BLACK_LEVEL


def cut(files, cues):
    """The animation's frames, each a frame's index or a fade (from, to, how far): from home lit, its focus moving to
    the latest titles, through a fade to « To watch » once its hero settled, to the film's page and Play's dim, a few
    black frames, then the lights coming up on home, where the loop starts again."""
    lit, watch, play = cues["lit"], cues["watch"], cues["play"]
    settled = watch + round(SETTLE_MS / cues["frame-ms"])
    home = [*range(lit, watch)]
    fade = [(watch - 1, settled + k, (k + 1) / (FADE + 1)) for k in range(FADE)]
    dark = next((i for i in range(play, len(files)) if black(files[i])), len(files))
    rest = [*range(settled + FADE, min(dark + BLACK_FRAMES, len(files)))]
    light = next((i for i in range(lit) if not black(files[i])), lit)
    return home + fade + rest + [*range(max(light - BLACK_FRAMES, 0), lit)]


class Reel:
    """Frames for Pillow's encoder, which reads a multi-frame image a frame at a time (n_frames, seek): each card is
    made as the encoder seeks it, so that never more than one is in memory. Everything else is the card's."""

    def __init__(self, count, make):
        self.n_frames = count
        self._make = make
        self._frame = None
        self._at = 0

    def seek(self, at):
        self._at = at
        self._frame = self._make(at)

    def tell(self):
        return self._at

    def __getattr__(self, name):
        if name.startswith("__") or self.__dict__.get("_frame") is None:
            raise AttributeError(name)
        return getattr(self._frame, name)


def tour(frames, out, width=TOUR_WIDTH, quality=72):
    """The animation, framed as a card the README's width, from home lit: its lights-up plays last, so the loop closes
    on it."""
    files = sorted(frames.glob("*.png"))
    if not files:
        return None
    cues = read_cues(frames)
    shots = cut(files, cues)

    def make(at):
        shot = shots[at]
        if isinstance(shot, tuple):
            start, end, t = shot
            with Image.open(files[start]) as a, Image.open(files[end]) as b:
                picture = Image.blend(a.convert("RGB"), b.convert("RGB"), t)
        else:
            with Image.open(files[shot]) as im:
                picture = im.convert("RGB")
        return card(picture, width, WIDE)

    first = make(0)
    # libwebp's slowest and best method. Not minimize_size, nor key frames further apart: both let a frame keep blocks
    # of the one before where they look alike, which a change of screen leaves behind, smudged.
    first.save(
        out, "WEBP", save_all=True, append_images=[Reel(len(shots) - 1, lambda at: make(at + 1))],
        duration=cues["frame-ms"], loop=0, quality=quality, method=6, minimize_size=False, allow_mixed=False,
    )
    return out, len(shots), len(shots) * cues["frame-ms"]


def webp_animates():
    """Whether this Pillow writes animated WebP: always from Pillow 12, where the feature is no longer named."""
    if not features.check("webp"):
        return False
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            return bool(features.check_feature("webp_anim"))
        except ValueError:
            return True


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("captures", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--tour-quality", type=int, default=72, help="the animation's lossy quality, 72 by default")
    parser.add_argument("--only", help="the images to make, by name (hero,social,cards,tour); all by default")
    args = parser.parse_args()
    if not webp_animates():
        sys.exit("This Pillow cannot write animated WebP: install tools/readme-images/requirements.txt")
    wanted = set(args.only.split(",")) if args.only else {"hero", "social", "cards", "tour"}
    stills = args.captures / "stills"
    args.out.mkdir(parents=True, exist_ok=True)
    written = []
    if wanted & {"hero", "social"}:
        home = Image.open(stills / "home.png")
        if "hero" in wanted:
            for name, wall in (("hero.webp", ROOM), ("hero-dark.webp", GITHUB_DARK)):
                hero(home, wall).save(args.out / name, "WEBP", quality=92, method=6)
                written.append((args.out / name, ""))
        if "social" in wanted:
            social(home).save(args.out / "social-preview.jpg", "JPEG", quality=90, optimize=True, progressive=True)
            written.append((args.out / "social-preview.jpg", ""))
    for name, (width, share, crop) in CARDS.items() if "cards" in wanted else []:
        still = stills / f"{name}.png"
        if not still.exists():
            print(f"no {still.name}: skipped", file=sys.stderr)
            continue
        card(Image.open(still), width, share, crop).save(args.out / f"{name}.webp", "WEBP", quality=86, method=6)
        written.append((args.out / f"{name}.webp", ""))
    if "tour" in wanted:
        made = tour(args.captures / "frames", args.out / "tour.webp", quality=args.tour_quality)
        if made:
            path, count, ms = made
            written.append((path, f"  {count} frames, {ms / 1000:.2f} s"))
    for path, more in written:
        with Image.open(path) as im:
            print(f"{path.name:20} {im.width:5} x {im.height:<5} {path.stat().st_size:>10,} bytes{more}")


if __name__ == "__main__":
    main()
