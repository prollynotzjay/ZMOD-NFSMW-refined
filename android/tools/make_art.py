"""NFSMW Refined artwork: launcher icon, launcher banner and launcher backdrop (original art, no EA logo).

Night-time Rockport feel: dark asphalt, an amber street-light glow, a pursuit light bar
(red / blue) along the top, speed streaks, a silver "NFSMW" and an amber "Refined" tag.

Usage:  python android/tools/make_art.py <repo root>
Writes res/drawable-nodpi/ic_launcher_{foreground,background,monochrome}.png,
res/drawable-nodpi/launcher_banner.png and assets/launcher/background.jpg.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

repo = sys.argv[1] if len(sys.argv) > 1 else '.'
fonts = os.path.join(repo, 'android/app/src/main/assets/fonts')
res = os.path.join(repo, 'android/app/src/main/res')

S = 1728  # work size (4x the xxxhdpi 432 px canvas)
AMBER = (255, 176, 0)


def nunito(size, weight=1000):
    f = ImageFont.truetype(os.path.join(fonts, 'Nunito.ttf'), size)
    f.set_variation_by_axes([weight])
    return f


def background():
    img = Image.new('RGB', (S, S), (10, 11, 14))
    px = img.load()
    # Vertical asphalt gradient with an amber street-light glow low in the frame.
    glow_c = (S * 0.5, S * 0.86)
    for y in range(S):
        for x in range(0, S, 2):
            t = y / S
            base = (int(10 + 10 * t), int(11 + 9 * t), int(14 + 6 * t))
            d = math.hypot(x - glow_c[0], (y - glow_c[1]) * 1.6) / (S * 0.75)
            g = max(0.0, 1 - d) ** 2.2
            c = (min(255, int(base[0] + 120 * g)), min(255, int(base[1] + 70 * g)), min(255, int(base[2] + 8 * g)))
            px[x, y] = c
            if x + 1 < S:
                px[x + 1, y] = c
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # Pursuit light bar glow: red on the left, blue on the right.
    d.ellipse((-S * 0.25, -S * 0.12, S * 0.55, S * 0.34), fill=(235, 30, 40, 210))
    d.ellipse((S * 0.45, -S * 0.12, S * 1.25, S * 0.34), fill=(30, 95, 255, 210))
    layer = layer.filter(ImageFilter.GaussianBlur(S * 0.07))
    img = Image.alpha_composite(img.convert('RGBA'), layer)
    # Speed streaks, slanted like motion.
    streaks = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(streaks)
    import random
    rnd = random.Random(7)
    for _ in range(46):
        y = rnd.uniform(S * 0.12, S * 0.95)
        x = rnd.uniform(-S * 0.3, S)
        length = rnd.uniform(S * 0.15, S * 0.55)
        w = rnd.uniform(2, 7)
        a = int(rnd.uniform(18, 60))
        d.line((x, y, x + length, y - length * 0.18), fill=(255, 255, 255, a), width=int(w))
    streaks = streaks.filter(ImageFilter.GaussianBlur(3))
    img = Image.alpha_composite(img, streaks)
    # Vignette.
    v = Image.new('L', (S, S), 0)
    ImageDraw.Draw(v).ellipse((-S * 0.15, -S * 0.15, S * 1.15, S * 1.15), fill=255)
    v = v.filter(ImageFilter.GaussianBlur(S * 0.12))
    dark = Image.new('RGBA', (S, S), (0, 0, 0, 255))
    img = Image.composite(img, dark, v)
    return img


def silver_text(text, font, skew=0.18):
    box = font.getbbox(text)
    w, h = box[2] - box[0] + 40, box[3] - box[1] + 40
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).text((20 - box[0], 20 - box[1]), text, font=font, fill=255)
    # Chrome: bright top, dark band in the middle, bright bottom edge.
    grad = Image.new('RGB', (w, h))
    gd = ImageDraw.Draw(grad)
    for y in range(h):
        t = y / h
        if t < 0.48:
            v = int(255 - 70 * (t / 0.48))
        elif t < 0.55:
            v = int(150 - 60 * ((t - 0.48) / 0.07))
        else:
            v = int(120 + 120 * ((t - 0.55) / 0.45))
        gd.line((0, y, w, y), fill=(v, v, min(255, v + 12)))
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    # Dark outline under the chrome.
    outline = mask.filter(ImageFilter.MaxFilter(17))
    under = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    under.paste((5, 6, 8, 255), (0, 0), outline)
    under.alpha_composite(out)
    # Italic slant, like a racing logo.
    extra = int(h * skew)
    under = under.transform((w + extra, h), Image.AFFINE, (1, skew, -extra, 0, 1, 0), Image.BICUBIC)
    return under


def foreground():
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    # The 66 dp safe zone of the 108 dp canvas is the central 61 %: keep the art inside it.
    title = silver_text('NFSMW', nunito(int(S * 0.20)))
    scale = (S * 0.60) / title.width
    title = title.resize((int(title.width * scale), int(title.height * scale)), Image.LANCZOS)
    shadow = Image.new('RGBA', title.size, (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 200), (0, 0), title.getchannel('A'))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    tx, ty = (S - title.width) // 2, int(S * 0.31)
    img.alpha_composite(shadow, (tx + 10, ty + 16))
    img.alpha_composite(title, (tx, ty))

    # "Refined" in a hand-written tag, amber with a dark edge, tilted a little.
    font = ImageFont.truetype(os.path.join(fonts, 'RockSalt-Regular.ttf'), int(S * 0.105))
    text = 'Refined'
    box = font.getbbox(text)
    w, h = box[2] - box[0] + 80, box[3] - box[1] + 80
    tag = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).text((40 - box[0], 40 - box[1]), text, font=font, fill=255)
    edge = m.filter(ImageFilter.MaxFilter(13))
    tag.paste((12, 8, 2, 255), (0, 0), edge)
    tag.paste(AMBER + (255,), (0, 0), m)
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    glow.paste(AMBER + (150,), (0, 0), m)
    glow = glow.filter(ImageFilter.GaussianBlur(22))
    glow.alpha_composite(tag)
    tag = glow.rotate(-6, resample=Image.BICUBIC, expand=True)
    scale = (S * 0.46) / tag.width
    tag = tag.resize((int(tag.width * scale), int(tag.height * scale)), Image.LANCZOS)
    img.alpha_composite(tag, ((S - tag.width) // 2 + int(S * 0.04), ty + title.height - int(S * 0.035)))

    # Amber underline stripe with a red/blue tip, under the tag.
    d = ImageDraw.Draw(img)
    y = int(S * 0.655)
    d.polygon([(S * 0.27, y), (S * 0.68, y), (S * 0.665, y + 18), (S * 0.255, y + 18)], fill=AMBER + (255,))
    d.polygon([(S * 0.69, y), (S * 0.705, y), (S * 0.69, y + 18), (S * 0.675, y + 18)], fill=(225, 35, 45, 255))
    d.polygon([(S * 0.715, y), (S * 0.73, y), (S * 0.715, y + 18), (S * 0.70, y + 18)], fill=(40, 100, 255, 255))
    return img


bg = background()
fg = foreground()
nodpi = os.path.join(res, 'drawable-nodpi')
os.makedirs(nodpi, exist_ok=True)
fg.resize((432, 432), Image.LANCZOS).save(os.path.join(nodpi, 'ic_launcher_foreground.png'), optimize=True)
bg.convert('RGB').resize((432, 432), Image.LANCZOS).save(os.path.join(nodpi, 'ic_launcher_background.png'), optimize=True)
# Monochrome (themed icons, Android 13+): the light shapes of the foreground.
mono = Image.new('RGBA', (S, S), (0, 0, 0, 0))
mono.paste((255, 255, 255, 255), (0, 0), fg.getchannel('A').point(lambda a: 255 if a > 140 else 0))
mono.resize((432, 432), Image.LANCZOS).save(os.path.join(nodpi, 'ic_launcher_monochrome.png'), optimize=True)


def banner():
    """Wide title for the launcher's top-left corner: chrome NFSMW, amber hand-written Refined."""
    title = silver_text('NFSMW', nunito(260), skew=0.2)
    font = ImageFont.truetype(os.path.join(fonts, 'RockSalt-Regular.ttf'), 120)
    box = font.getbbox('Refined')
    w, h = box[2] - box[0] + 80, box[3] - box[1] + 80
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).text((40 - box[0], 40 - box[1]), 'Refined', font=font, fill=255)
    tag = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    tag.paste((12, 8, 2, 255), (0, 0), m.filter(ImageFilter.MaxFilter(11)))
    tag.paste(AMBER + (255,), (0, 0), m)
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    glow.paste(AMBER + (120,), (0, 0), m)
    glow = glow.filter(ImageFilter.GaussianBlur(16))
    glow.alpha_composite(tag)
    tag = glow.rotate(-5, resample=Image.BICUBIC, expand=True)
    out = Image.new('RGBA', (title.width + tag.width // 2 + 40, title.height + tag.height // 2 + 10), (0, 0, 0, 0))
    out.alpha_composite(title, (0, 0))
    out.alpha_composite(tag, (title.width - tag.width // 2 - 20, title.height - tag.height // 2 - 40))
    return out.crop(out.getbbox())


banner().save(os.path.join(nodpi, 'launcher_banner.png'), optimize=True)


def backdrop():
    """Night street behind the menu: wide version of the icon background with longer speed streaks."""
    W, H = 1600, 900
    img = Image.new('RGB', (W, H), (8, 9, 12))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        d.line((0, y, W, y), fill=(int(8 + 14 * t), int(9 + 10 * t), int(12 + 6 * t)))
    layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.ellipse((W * 0.45, H * 0.55, W * 1.25, H * 1.35), fill=(255, 150, 20, 120))      # street light glow
    ld.ellipse((W * 0.55, -H * 0.35, W * 0.9, H * 0.25), fill=(230, 30, 40, 120))       # pursuit red
    ld.ellipse((W * 0.85, -H * 0.35, W * 1.2, H * 0.25), fill=(30, 95, 255, 120))       # pursuit blue
    layer = layer.filter(ImageFilter.GaussianBlur(120))
    img = Image.alpha_composite(img.convert('RGBA'), layer)
    import random
    rnd = random.Random(11)
    streaks = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(streaks)
    for _ in range(90):
        y = rnd.uniform(H * 0.35, H)
        x = rnd.uniform(-W * 0.2, W)
        length = rnd.uniform(W * 0.1, W * 0.45)
        color = AMBER if rnd.random() < 0.35 else (255, 255, 255)
        sd.line((x, y, x + length, y - length * 0.12), fill=color + (int(rnd.uniform(15, 55)),),
                width=int(rnd.uniform(2, 6)))
    img = Image.alpha_composite(img, streaks.filter(ImageFilter.GaussianBlur(2)))
    return img.convert('RGB')


os.makedirs(os.path.join(repo, 'android/app/src/main/assets/launcher'), exist_ok=True)
backdrop().save(os.path.join(repo, 'android/app/src/main/assets/launcher/background.jpg'), quality=86, optimize=True)
os.makedirs(os.path.join(repo, 'extras'), exist_ok=True)
fullicon = bg.copy()
fullicon.alpha_composite(fg)
fullicon.resize((512, 512), Image.LANCZOS).save(os.path.join(repo, 'extras/icon.png'))
print('art written')
