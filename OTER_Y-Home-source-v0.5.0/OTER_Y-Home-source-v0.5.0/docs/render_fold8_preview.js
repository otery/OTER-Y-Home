const fs = require('fs');
const sharp = require('sharp');

const CREAM = '#f6f7e2';
const INK = '#151b17';
const ORANGE = '#ff7138';
const PANEL = '#f1f2dc';
const MUTED = '#e4e6d2';
const YELLOW = '#ffda1a';
const FONT = 'Arial, Noto Sans CJK KR, sans-serif';

function esc(value) {
  return String(value).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
}

function status(x, y, right, battery = 72) {
  const bx = x + right - 92;
  return `<g fill="${INK}" opacity=".48">
    <rect x="${bx-155}" y="${y+20}" width="8" height="12" rx="2"/>
    <rect x="${bx-140}" y="${y+14}" width="8" height="18" rx="2"/>
    <rect x="${bx-125}" y="${y+7}" width="8" height="25" rx="2"/>
    <rect x="${bx-110}" y="${y}" width="8" height="32" rx="2"/>
    <g fill="none" stroke="${INK}" stroke-width="5" stroke-linecap="round">
      <path d="M${bx-82} ${y+13}q22-20 44 0"/><path d="M${bx-72} ${y+23}q12-10 24 0"/>
    </g><circle cx="${bx-60}" cy="${y+30}" r="4"/>
    <text x="${bx}" y="${y+29}" font-family="${FONT}" font-size="24" text-anchor="end">${battery}%</text>
    <rect x="${bx+12}" y="${y+3}" width="58" height="30" rx="8" fill="none" stroke="${INK}" stroke-width="4"/>
    <rect x="${bx+74}" y="${y+11}" width="6" height="14" rx="2"/>
    <rect x="${bx+18}" y="${y+9}" width="${Math.round(46*battery/100)}" height="18" rx="4"/>
  </g>`;
}

function wave(x, y, scale = 1) {
  const heights = [12,24,16,38,56,42,22,48,30];
  return `<g stroke="${INK}" stroke-width="${4.5*scale}" stroke-linecap="round">${heights.map((height,col)=>
    `<line x1="${x+col*18*scale}" y1="${y-height*scale/2}" x2="${x+col*18*scale}" y2="${y+height*scale/2}"/>`).join('')}</g>`;
}

function calendar(cx, cy, dx, dy, radius) {
  const first = 6; // August 1, 2026 is Saturday.
  let out = '<g>';
  for (let cell=0; cell<42; cell++) {
    const day = cell-first+1;
    const inMonth = day >= 1 && day <= 31;
    const fill = day===26 ? ORANGE : inMonth ? INK : MUTED;
    const opacity = inMonth ? 1 : .64;
    out += `<circle cx="${cx+(cell%7-3)*dx}" cy="${cy+(Math.floor(cell/7)-2.5)*dy}" r="${day===26?radius*1.1:radius}" fill="${fill}" opacity="${opacity}"/>`;
  }
  return out + '</g>';
}

function tile(x,y,w,h,label,{circle=false,orange=false,dot=false}={}) {
  const size = Math.min(w*.57,h*.62);
  const ix=x+(w-size)/2, iy=y+18;
  return `<g><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="22" fill="${PANEL}" opacity=".48"/>
    ${circle?`<circle cx="${x+w/2}" cy="${iy+size/2}" r="${size/2}" fill="${orange?ORANGE:INK}"/>`:`<rect x="${ix}" y="${iy}" width="${size}" height="${size}" rx="${size*.18}" fill="${orange?ORANGE:INK}"/>`}
    ${dot?`<circle cx="${ix+size-8}" cy="${iy+9}" r="8" fill="${orange?'#fff':YELLOW}"/>`:''}
    ${circle?`<circle cx="${x+w/2+size*.29}" cy="${iy+size/2-size*.22}" r="8" fill="${YELLOW}"/>`:''}
    <text x="${x+w/2}" y="${y+h-19}" font-family="${FONT}" font-size="25" text-anchor="middle" fill="${INK}" opacity=".86">${esc(label)}</text></g>`;
}

function fader(x,y,w,h) {
  const cx=x+w/2, top=y+105, bottom=y+h-100, knob=bottom-(bottom-top)*.56;
  return `<g><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="22" fill="${PANEL}" opacity=".48"/>
    <text x="${cx}" y="${y+40}" font-family="${FONT}" font-size="22" text-anchor="middle" fill="${INK}" opacity=".28">Spotify ↑</text>
    <line x1="${cx}" y1="${top}" x2="${cx}" y2="${bottom}" stroke="${INK}" stroke-width="5"/>
    <rect x="${cx-52}" y="${knob-22}" width="104" height="44" rx="9" fill="${INK}"/><line x1="${cx-32}" y1="${knob}" x2="${cx+32}" y2="${knob}" stroke="${ORANGE}" stroke-width="7"/>
    <text x="${cx}" y="${y+h-50}" font-family="${FONT}" font-size="20" text-anchor="middle" fill="${INK}" opacity=".25">VOLUME</text>
    <text x="${cx}" y="${y+h-18}" font-family="${FONT}" font-size="18" text-anchor="middle" fill="${INK}" opacity=".34">CAMERA ↓</text></g>`;
}

function playButton(x,y,s) {
  return `<rect x="${x-s*.55}" y="${y-s}" width="${s*.38}" height="${s*2}" rx="6" fill="${INK}"/><rect x="${x+s*.18}" y="${y-s}" width="${s*.38}" height="${s*2}" rx="6" fill="${INK}"/>`;
}

const innerX=86, innerY=78, innerW=1828, innerH=2448;
const coverX=2250, coverY=330, coverW=1248, coverH=1972;
let svg = `<svg xmlns="http://www.w3.org/2000/svg" width="3650" height="2670" viewBox="0 0 3650 2670">
<rect width="3650" height="2670" fill="#b88a62"/>
<text x="86" y="2615" font-family="${FONT}" font-size="29" fill="#fff">GALAXY Z FOLD8 · INNER 1828 × 2448</text>
<text x="2250" y="2415" font-family="${FONT}" font-size="29" fill="#fff">GALAXY Z FOLD8 · COVER 1248 × 1972</text>
<rect x="40" y="32" width="1920" height="2538" rx="92" fill="#0f1511"/><rect x="${innerX}" y="${innerY}" width="${innerW}" height="${innerH}" rx="54" fill="${CREAM}"/>
<circle cx="${innerX+innerW/2}" cy="${innerY+31}" r="19" fill="#101512"/>
${status(innerX,innerY+52,innerW-38,72)}
<text x="${innerX+128}" y="${innerY+235}" transform="rotate(90 ${innerX+128} ${innerY+235})" font-family="${FONT}" font-weight="700" font-size="68" letter-spacing="5" fill="${INK}">OTER_Y</text>
<text x="${innerX+innerW-90}" y="${innerY+190}" font-family="${FONT}" font-size="31" text-anchor="end" fill="${INK}" opacity=".34">It Runs Through Me (feat. De La Soul) …</text>
${wave(innerX+innerW-315,innerY+270,1.0)}${playButton(innerX+innerW-115,innerY+270,28)}
${calendar(innerX+innerW*.56,innerY+790,126,112,20)}
<text x="${innerX+104}" y="${innerY+1585}" font-family="${FONT}" font-weight="700" font-size="82" letter-spacing="1" fill="${INK}">19:35<tspan font-size="52" font-weight="400" fill="${INK}" opacity=".18">:20</tspan></text>
<text x="${innerX+innerW-104}" y="${innerY+1585}" font-family="${FONT}" font-size="52" letter-spacing="4" text-anchor="end" fill="${INK}" opacity=".17">08.26 WED</text>
${fader(innerX+102,innerY+1650,220,650)}
${tile(innerX+360,innerY+1650,320,300,'Claude')}${tile(innerX+710,innerY+1650,320,300,'YouTube',{dot:true})}${tile(innerX+1060,innerY+1650,320,300,'Instagram',{dot:true})}${tile(innerX+1410,innerY+1650,320,300,'Media',{circle:true})}
${tile(innerX+360,innerY+2000,320,300,'Gmail',{orange:true,dot:true})}${tile(innerX+710,innerY+2000,320,300,'Chrome')}${tile(innerX+1060,innerY+2000,320,300,'LINE')}${tile(innerX+1410,innerY+2000,320,300,'Work',{circle:true})}
<circle cx="${innerX+innerW/2-14}" cy="${innerY+2393}" r="11" fill="${INK}"/><circle cx="${innerX+innerW/2+27}" cy="${innerY+2393}" r="8" fill="${MUTED}"/>

<rect x="2197" y="272" width="1354" height="2088" rx="104" fill="#0f1511"/><rect x="${coverX}" y="${coverY}" width="${coverW}" height="${coverH}" rx="65" fill="${CREAM}"/>
<circle cx="${coverX+coverW/2}" cy="${coverY+28}" r="16" fill="#101512"/>
${status(coverX,coverY+52,coverW-42,68)}
<text x="${coverX+72}" y="${coverY+170}" font-family="${FONT}" font-weight="700" font-size="62" letter-spacing="4" fill="${INK}">OTER_Y</text>
<text x="${coverX+coverW-70}" y="${coverY+210}" font-family="${FONT}" font-size="23" text-anchor="end" fill="${INK}" opacity=".34">It Runs Through Me (feat. De La Soul) …</text>
${wave(coverX+coverW-220,coverY+275,.72)}${playButton(coverX+coverW-80,coverY+275,20)}
${calendar(coverX+coverW/2,coverY+650,84,78,14)}
<text x="${coverX+76}" y="${coverY+1015}" font-family="${FONT}" font-weight="700" font-size="63" fill="${INK}">19:35<tspan font-size="39" font-weight="400" opacity=".18">:20</tspan></text>
<text x="${coverX+coverW-72}" y="${coverY+1015}" font-family="${FONT}" font-size="34" letter-spacing="2" text-anchor="end" fill="${INK}" opacity=".17">08.26 WED</text>
${fader(coverX+70,coverY+1080,185,760)}
${tile(coverX+292,coverY+1080,400,170,'Claude')}${tile(coverX+726,coverY+1080,400,170,'YouTube',{dot:true})}
${tile(coverX+292,coverY+1275,400,170,'Instagram',{dot:true})}${tile(coverX+726,coverY+1275,400,170,'Media',{circle:true})}
${tile(coverX+292,coverY+1470,400,170,'Gmail',{orange:true,dot:true})}${tile(coverX+726,coverY+1470,400,170,'Chrome')}
${tile(coverX+292,coverY+1665,400,170,'LINE')}${tile(coverX+726,coverY+1665,400,170,'Work',{circle:true})}
<circle cx="${coverX+coverW/2-12}" cy="${coverY+1915}" r="10" fill="${INK}"/><circle cx="${coverX+coverW/2+24}" cy="${coverY+1915}" r="7" fill="${MUTED}"/>

</svg>`;

fs.writeFileSync('docs/preview.svg', svg);
sharp(Buffer.from(svg)).png().toFile('docs/preview.png').then(() => {
  console.log('Fold8 preview rendered at 3650×2670');
});
