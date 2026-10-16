"""Build a standalone example using the app's actual stats renderer and fictional data.
Run from the repository root. No phone, backend or real game data is accessed.
"""
from pathlib import Path
import json
import re

panel = Path('src/main/assets/panel.html').read_text()
css = re.search(r'<style>(.*?)</style>', panel, re.S).group(1)
helpers = '\n'.join(re.search(r'^function '+name+r'\(.*$', panel, re.M).group() for name in ['esc', 'dur', 'pct', 'dot'])
colors = re.search(r'var SEAT_COLOURS=\{[^}]+\}', panel).group() + ';'
renderer = panel[panel.index('function gameStatsHtml('):panel.index('function renderAllTime(')]
seats = []
for seat,total,longest,points in [('blue',3000000,300000,11),('red',2400000,260000,9),('green',2280000,240000,8),('yellow',1920000,220000,7)]:
    seats.append(dict(seat=seat,name=seat.title(),turns=20,totalMs=total,share=total/9600000,averageMs=total/20,longestMs=longest,victoryPoints=points,winner=seat=='blue'))
stats = dict(gameMs=10800000,rounds=10,turns=80,averageTurnMs=120000,winner='blue',winnerName='Blue',seats=seats,
    kinds=[dict(kind='agent',turns=60,averageMs=120000,totalMs=7200000,share=.75),dict(kind='reveal',turns=20,averageMs=120000,totalMs=2400000,share=.25)],
    roundTimes=[dict(round=i+1,turnTimeMs=t*60000) for i,t in enumerate([12,14,15,16,17,18,21,19,15,13])],slowestRound=7,
    checkpoints=[dict(conflict=5,final=False,victoryPoints=dict(blue=6,red=5,green=4,yellow=3)),dict(conflict=9,final=False,victoryPoints=dict(blue=9,red=8,green=7,yellow=6)),dict(conflict=10,final=True,victoryPoints=dict(blue=11,red=9,green=8,yellow=7))])
html='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Dune Companion · Offline stats example</title><style>'''+css+'''\nbody{max-width:720px;margin:auto}#tabs .on{border-color:var(--fire)}</style><h1>GAME COMPANION</h1><p class="sub">DUNE: IMPERIUM · LOCAL STATS</p><nav id="tabs"><button>GAME</button><button class="on">STATS</button><button>GUIDE</button></nav><p class="note">OFFLINE EXAMPLE · FICTIONAL GAME DATA AND SEAT NAMES.<br>Rendered with the app's stats code. This preview has no connection to a phone.</p><h2>FINISHED GAME</h2><main id="example"></main><script>'''+colors+helpers+renderer+'\ndocument.getElementById("example").innerHTML=gameStatsHtml('+json.dumps(stats)+');</script></html>'
Path('docs/local-stats-example.html').write_text(html)
