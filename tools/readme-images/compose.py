"""Frames the README's captures (app/src/test/.../readme, CONTRIBUTING: README's images) for the README.

    python3 -I tools/readme-images/compose.py CAPTURES OUT

CAPTURES holds what the capture test wrote: stills/<name>.png at 1920 x 1080 and frames/NNNN.jpg, a frame every
32 ms. OUT receives:

- hero.webp, home on a TV in a dark room lit by its own picture (bias lighting), 2400 px wide;
- social-preview.jpg, GitHub's link preview, 1280 x 640;
- a card per screen (film.webp, show.webp...): rounded, a hairline at its edge and a soft shadow on a transparent
  ground, so it reads on GitHub's light and dark themes;
- tour.webp, the animation, framed as the cards are, looping.

Pillow only, no text drawn: captions stay in the README. Run with Pillow 11 (tools/readme-images/requirements.txt).
"""

import argparse
import re
import sys
import warnings
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, features

# The room: pure black, as the app's own (DESIGN.md, Colour).
ROOM = (0, 0, 0)

# Supersampling for the curves: masks are drawn at this scale, then reduced.
SS = 4

# The screens the README shows, framed as cards, by the still each comes from.
CARDS = ["film", "show", "saga", "library", "watch", "settings", "to-identify", "identify", "update"]

# The screen inside a card, and around it the room the shadow takes.
CARD_WIDTH = 1200
TOUR_WIDTH = 1200

# The animation: a frame every 32 ms (the capture's), starting on home lit, its lights-up last.
FRAME_MS = 32
LIGHTS_UP_FRAMES = 32

TITLE_CARD = Path(__file__).resolve().parents[2] / "docs" / "images" / "houselights.svg"


def rounded_mask(size, radius):
    """An antialiased mask of a rectangle of [size] with corners of [radius]."""
    w, h = size
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius * SS, fill=255)
    return big.resize(size, Image.LANCZOS)


def ring_mask(size, radius, width):
    """An antialiased mask of a hairline [width] wide just inside a rounded rectangle's edge."""
    w, h = size
    big = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(big)
    d.rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius * SS, fill=255)
    inset = round(width * SS)
    d.rounded_rectangle([inset, inset, w * SS - 1 - inset, h * SS - 1 - inset], max(radius * SS - inset, 0), fill=0)
    return big.resize(size, Image.LANCZOS)


def scaled(im, width):
    """[im] at [width], its height kept in ratio, reduced with Lanczos."""
    height = round(im.height * width / im.width)
    return im.resize((width, height), Image.LANCZOS)


def card(shot, width=CARD_WIDTH, transparent=True):
    """A screen as a card: rounded corners, a hairline at its edge, a soft shadow beneath, on a transparent ground."""
    screen = scaled(shot.convert("RGB"), width)
    w, h = screen.size
    radius = round(w * 0.016)
    pad = round(w * 0.045)
    canvas = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    # Two shadows, as light falls on a thing a little above its ground: a wide soft one, and a tighter one under it.
    for blur, drop, opacity in ((w * 0.026, w * 0.012, 0.30), (w * 0.008, w * 0.004, 0.22)):
        shade = Image.new("L", canvas.size, 0)
        shade.paste(rounded_mask((w, h), radius).point(lambda v, o=opacity: round(v * o)), (pad, pad + round(drop)))
        shade = shade.filter(ImageFilter.GaussianBlur(blur))
        canvas.alpha_composite(Image.merge("RGBA", (*[Image.new("L", canvas.size, 0)] * 3, shade)))
    face = screen.convert("RGBA")
    face.putalpha(rounded_mask((w, h), radius))
    # The hairline: projector white at 14 % over the picture's edge, which a black screen on a dark page needs.
    edge = Image.new("RGBA", (w, h), (244, 244, 245, 255))
    edge.putalpha(ring_mask((w, h), radius, 1.5).point(lambda v: round(v * 0.14)))
    face.alpha_composite(edge)
    canvas.alpha_composite(face, (pad, pad))
    return canvas if transparent else canvas.convert("RGB")


def bias_light(screen, size, at, spread=1.5, strength=0.62):
    """The light a TV's back throws on the wall: [screen]'s own colours, spread and blurred far around it."""
    w, h = size
    sw, sh = screen.size
    small = screen.convert("RGB").resize((48, 27), Image.BOX)
    small = ImageEnhance.Color(small).enhance(1.5)
    # The edges' colours reach furthest: the picture extended at its borders, then blurred into a glow.
    gw, gh = round(sw * spread), round(sh * spread)
    glow = small.resize((gw // 8, gh // 8), Image.BILINEAR)
    pad = max(gw, gh) // 8
    room = Image.new("RGB", (w // 8 + 2 * pad, h // 8 + 2 * pad), ROOM)
    cx, cy = at[0] + sw // 2, at[1] + sh // 2
    room.paste(glow, ((cx - gw // 2) // 8 + pad, (cy - gh // 2) // 8 + pad))
    room = room.filter(ImageFilter.GaussianBlur(sw / 8 * 0.11))
    room = room.crop((pad, pad, pad + w // 8, pad + h // 8)).resize((w, h), Image.BICUBIC)
    # Falling off away from the set, wider than tall, as a wall lit from behind a screen.
    falloff = Image.new("L", (w // 8, h // 8), 0)
    d = ImageDraw.Draw(falloff)
    ex, ey = sw * spread * 0.62 / 8, sh * spread * 0.70 / 8
    d.ellipse([cx / 8 - ex, cy / 8 - ey, cx / 8 + ex, cy / 8 + ey], fill=255)
    falloff = falloff.filter(ImageFilter.GaussianBlur(sw / 8 * 0.16)).resize((w, h), Image.BICUBIC)
    falloff = falloff.point(lambda v: round(v * strength))
    return Image.composite(room, Image.new("RGB", (w, h), ROOM), falloff)


def tv(shot, width=2400, height=1350, screen_width=1800, top=120):
    """[shot] on a TV in a dark room, lit by its own picture: a thin bezel, a slim stand and its shadow."""
    screen = scaled(shot.convert("RGB"), screen_width)
    sw, sh = screen.size
    x = (width - sw) // 2
    room = bias_light(screen, (width, height), (x, top))
    room = room.convert("RGBA")
    d = ImageDraw.Draw(room)
    bezel = round(sw * 0.0055)
    cx = width // 2
    foot_top = top + sh + bezel
    # The stand: a slim neck on a low plate, its shadow on the floor, the floor taking a little of the light.
    neck_w, neck_h = round(sw * 0.045), round(sw * 0.032)
    plate_w, plate_h = round(sw * 0.30), round(sw * 0.0075)
    floor = foot_top + neck_h + plate_h
    shadow = Image.new("L", room.size, 0)
    ImageDraw.Draw(shadow).ellipse([cx - plate_w * 0.62, floor - plate_h * 0.6, cx + plate_w * 0.62, floor + plate_h * 2.4], fill=210)
    shadow = shadow.filter(ImageFilter.GaussianBlur(sw * 0.012))
    room.alpha_composite(Image.merge("RGBA", (*[Image.new("L", room.size, 0)] * 3, shadow)))
    d = ImageDraw.Draw(room)
    d.rounded_rectangle([cx - neck_w // 2, foot_top - 4, cx + neck_w // 2, foot_top + neck_h], 3, fill=(16, 16, 18, 255))
    d.rounded_rectangle([cx - plate_w // 2, foot_top + neck_h, cx + plate_w // 2, floor], plate_h // 2, fill=(22, 22, 25, 255))
    d.line([cx - plate_w // 2 + plate_h, foot_top + neck_h, cx + plate_w // 2 - plate_h, foot_top + neck_h], fill=(46, 46, 52, 255), width=1)
    # The set: a thin black bezel, its outer edge catching a little of the room's light; then the picture.
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


def hero(shot):
    """Home on the TV, 2400 px wide, the room's corners rounded off the page."""
    room = tv(shot)
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
    """The lockup, Gradation in its light beside the name, cut from the README's title card (docs/images), [height] tall."""
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
    # The mark above the name on the card: set beside it, its bars as tall as 1.4 times the name's capitals.
    mark_box, name_box = mark.getbbox(), name.getbbox()
    mark_im, name_im = mark.crop(mark_box), name.crop(name_box)
    caps = name_im.height
    mark_im = mark_im.resize((round(mark_im.width * caps * 1.4 / mark_im.height), round(caps * 1.4)), Image.LANCZOS)
    gap = round(mark_im.width * 0.62 * 0.6)
    lw, lh = mark_im.width + gap + name_im.width, mark_im.height
    alpha = Image.new("L", (lw, lh), 0)
    alpha.paste(mark_im, (0, 0))
    alpha.paste(name_im, (mark_im.width + gap, (lh - caps) // 2))
    colour = Image.new("RGB", (lw, lh), (244, 244, 245))
    ember = Image.linear_gradient("L").rotate(90).resize((mark_im.width, lh))
    light = Image.merge("RGB", [ember.point(lambda v, a=a, b=b: round(a + (b - a) * (255 - v) / 255)) for a, b in zip((255, 159, 28), (255, 46, 99))])
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
                points.append(((1 - t) ** 2 * x + 2 * (1 - t) * t * qx + t * t * ex, (1 - t) ** 2 * y + 2 * (1 - t) * t * qy + t * t * ey))
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


def tour(frames, out, width=TOUR_WIDTH, quality=80):
    """The animation, framed as a card, from the home frame lit: its lights-up plays last, so the loop closes on it."""
    files = sorted(frames.glob("*.jpg"))
    if not files:
        return None
    order = files[LIGHTS_UP_FRAMES:] + files[:LIGHTS_UP_FRAMES]
    shown = [card(Image.open(f), width) for f in order]
    first, rest = shown[0], shown[1:]
    first.save(
        out, "WEBP", save_all=True, append_images=rest, duration=FRAME_MS, loop=0,
        quality=quality, method=4, minimize_size=False, allow_mixed=False,
    )
    return out


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
    parser.add_argument("--tour-quality", type=int, default=80)
    args = parser.parse_args()
    if not webp_animates():
        sys.exit("This Pillow cannot write animated WebP: install tools/readme-images/requirements.txt")
    stills = args.captures / "stills"
    args.out.mkdir(parents=True, exist_ok=True)
    written = []
    home = Image.open(stills / "home.png")
    hero(home).save(args.out / "hero.webp", "WEBP", quality=88, method=6)
    written.append(args.out / "hero.webp")
    social(home).save(args.out / "social-preview.jpg", "JPEG", quality=90, optimize=True, progressive=True)
    written.append(args.out / "social-preview.jpg")
    for name in CARDS:
        still = stills / f"{name}.png"
        if not still.exists():
            print(f"no {still.name}: skipped", file=sys.stderr)
            continue
        card(Image.open(still)).save(args.out / f"{name}.webp", "WEBP", quality=86, method=6)
        written.append(args.out / f"{name}.webp")
    made = tour(args.captures / "frames", args.out / "tour.webp", quality=args.tour_quality)
    if made:
        written.append(made)
    for path in written:
        with Image.open(path) as im:
            frames = getattr(im, "n_frames", 1)
            print(f"{path.name:22} {im.width:5} x {im.height:<5} {path.stat().st_size:>10,} bytes" + (f"  {frames} frames" if frames > 1 else ""))


if __name__ == "__main__":
    main()
