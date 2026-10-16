# Run from the repository root with Python 3 and Pillow installed.
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

out = Path('docs/media')
font = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
bold = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
f = lambda size, b=False: ImageFont.truetype(bold if b else font, size)
frames = []
scenes = [
    ('DOUBLE TAP OR SPIN', 'End your agent turn', '2 taps, or one full turn flat on the table', 48),
    ('TRIPLE TAP OR SHAKE', 'Reveal or resolve combat', '3 taps, or shake the phone in hand', 48),
    ('DRAW S · C · M', 'Claim for the current player', '', 72),
]
# Finger trails, illustrated rather than font glyphs.
letters = [
    [(115,95),(100,85),(77,85),(58,100),(58,116),(78,130),(102,140),(115,155),(111,174),(94,185),(72,185),(54,176)],
    [(116,96),(101,85),(77,85),(58,99),(51,126),(54,155),(70,178),(94,185),(115,175)],
    [(51,185),(51,85),(85,142),(119,85),(119,185)],
]
for scene, (title, sub, hint, steps) in enumerate(scenes):
    for step in range(steps):
        im = Image.new('RGB', (800,440), '#100f0d'); d = ImageDraw.Draw(im)
        d.text((36,26), 'DUNE COMPANION', font=f(14,True), fill='#b4aa94')
        d.text((36,93), title, font=f(28 if scene<2 else 34,True), fill='#ee873d')
        d.text((36,149), sub, font=f(21), fill='#f5eedf')
        if scene==2:
            hint = ['S · Swordmaster','C · High Council','M · Mentat'][step//24]
        d.text((36,337), hint, font=f(15), fill='#b4aa94')
        d.text((36,393), f'{scene+1} / 3     GESTURE GUIDE', font=f(12), fill='#807768')
        phone=Image.new('RGBA',(170,270)); pd=ImageDraw.Draw(phone)
        pd.rounded_rectangle((4,4,165,265),radius=25,fill='#1b1a17',outline='#72634d',width=3)
        pd.rounded_rectangle((14,20,155,248),radius=14,fill='#0b0b09')
        pd.text((34,43),'PLAYER TURN',font=f(12,True),fill='#b4aa94')
        if scene==2:
            points=letters[step//24]; progress=min(1,(step%24+1)/16)
            # Reveal the path by distance so every letter draws at a steady speed.
            lengths=[math.dist(a,b) for a,b in zip(points,points[1:])]
            remaining=sum(lengths)*progress; trail=[points[0]]
            for a,b,length in zip(points,points[1:],lengths):
                if remaining>=length: trail.append(b); remaining-=length
                else:
                    trail.append((a[0]+(b[0]-a[0])*remaining/length,a[1]+(b[1]-a[1])*remaining/length));break
            pd.line(trail,fill='#ee873d',width=5,joint='curve')
        else:
            pd.text((53,100),'BLUE',font=f(20,True),fill='#ed873b')
            pd.text((43,140),'02:34',font=f(25),fill='#ed873b')
        shaking=scene==1 and step>=24
        if (scene==0 and step<24) or (scene==1 and not shaking):
            n=2 if scene==0 else 3
            for j in range(n):
                x=85+(j-(n-1)/2)*26; active=3+j*5<=step<8+j*5
                pd.ellipse((x-7,208,x+7,222),fill='#f5b36b' if active else '#51402c')
                if active: pd.ellipse((x-16,199,x+16,231),outline='#ee873d',width=2)
        if scene==0 and step>=24: phone=phone.rotate(-360*(step-24)/24,resample=Image.Resampling.BICUBIC,expand=True)
        if shaking:
            phone=phone.rotate(12*math.sin(step*math.pi/2),resample=Image.Resampling.BICUBIC,expand=True)
            for x in (510,730):d.line((x,180,x-10,200,x,220,x-10,240),fill='#ee873d',width=3)
        offset=round(12*math.sin(step*math.pi/2)) if shaking else 0
        im.paste(phone,(620-phone.width//2+offset,220-phone.height//2),phone)
        frames.append(im)
frames[0].save(out/'gestures.gif',save_all=True,append_images=frames[1:],duration=100,loop=0,optimize=True)
