#!/usr/bin/env python3
"""Build the offline single-file demo. Standard library only."""
from pathlib import Path
import base64
import re
root=Path(__file__).resolve().parent
css=(root/'src/fonts.css').read_text()
def inline_font(match):
    relative=match[1]
    file=(root/'src'/relative).resolve()
    if not file.is_relative_to(root/'assets/fonts'):
        raise ValueError('Unexpected font path')
    data=base64.b64encode(file.read_bytes()).decode('ascii')
    mime='font/woff2' if file.suffix=='.woff2' else 'font/ttf'
    return 'url(data:'+mime+';base64,'+data+')'
css=re.sub(r'url\((\.\./assets/fonts/[^)]+)\)',inline_font,css)
style=(root/'src/style.css').read_text()
script=(root/'src/app.js').read_text()
assert '</script' not in script.lower(), 'Script must not close its own tag'
html=(root/'src/shell.html').read_text().replace('/*__FONTS__*/',css).replace('/*__STYLE__*/',style).replace('/*__SCRIPT__*/',script)
assert all(x not in html for x in ['/*__FONTS__*/','/*__STYLE__*/','/*__SCRIPT__*/'])
favicon=base64.b64encode((root/'assets/infra-hub.svg').read_bytes()).decode('ascii')
html=html.replace('<title>', '<link rel="icon" href="data:image/svg+xml;base64,'+favicon+'">\n<title>',1)
licenses='\n\n'.join(p.name+'\n'+p.read_text() for p in sorted((root/'assets/fonts').glob('*-OFL.txt')))
html=html.replace('<head>','<!-- Embedded font licenses\n'+licenses.replace('-->', '-- >')+'\n-->\n<head>',1)
(root/'index.html').write_text(html)
print(f'Built {root / "index.html"} ({len(html.encode()):,} bytes)')
