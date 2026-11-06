'use strict';

/**
 * Gera os icones do PWA sem dependencias externas (PNG RGBA na mao).
 *   node tools/make-icons.js
 */

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const OUT_DIR = path.join(__dirname, '..', 'public', 'icons');
const SIZES = [512, 192, 180];

// ---------------------------------------------------------------- CRC32
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (let i = 0; i < buffer.length; i += 1) {
    crc = CRC_TABLE[(crc ^ buffer[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typeBuffer = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
  return Buffer.concat([length, typeBuffer, data, crc]);
}

function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (stride + 1)] = 0; // filtro "None"
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // 8 bits por canal
  header[9] = 6; // RGBA
  header[10] = 0;
  header[11] = 0;
  header[12] = 0;

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ------------------------------------------------------------- desenho
const clamp01 = (value) => Math.min(1, Math.max(0, value));
const mix = (a, b, t) => a + (b - a) * t;

function insideRoundedSquare(x, y, size, radius) {
  const dx = Math.abs(x - size / 2) - (size / 2 - radius);
  const dy = Math.abs(y - size / 2) - (size / 2 - radius);
  if (dx <= 0 && dy <= 0) return true;
  const qx = Math.max(dx, 0);
  const qy = Math.max(dy, 0);
  return Math.hypot(qx, qy) <= radius;
}

function render(size) {
  const samples = 3;
  const pixels = Buffer.alloc(size * size * 4);

  const center = size / 2;
  const radius = size * 0.225;
  const ringRadius = size * 0.245;
  const ringWidth = size * 0.062;
  const stemWidth = ringWidth * 1.02;
  const gapHalfAngle = 0.44;

  const stemTop = center - ringRadius - ringWidth * 0.6;
  const stemBottom = center - ringRadius * 0.12;

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      let red = 0;
      let green = 0;
      let blue = 0;
      let covered = 0;

      for (let sy = 0; sy < samples; sy += 1) {
        for (let sx = 0; sx < samples; sx += 1) {
          const px = x + (sx + 0.5) / samples;
          const py = y + (sy + 0.5) / samples;

          if (!insideRoundedSquare(px, py, size, radius)) continue;

          const t = py / size;
          let r = mix(26, 9, t);
          let g = mix(31, 11, t);
          let b = mix(42, 16, t);

          const dx = px - center;
          const dy = py - center;
          const distance = Math.hypot(dx, dy);

          // brilho quente atras do simbolo
          const glow = clamp01(1 - distance / (size * 0.62)) ** 2 * 0.55;
          r = mix(r, 255, glow * 0.35);
          g = mix(g, 150, glow * 0.35);
          b = mix(b, 50, glow * 0.35);

          const angle = Math.atan2(dx, -dy);
          const onRing = Math.abs(distance - ringRadius) <= ringWidth / 2 && Math.abs(angle) > gapHalfAngle;
          const onStem = Math.abs(dx) <= stemWidth / 2 && py >= stemTop && py <= stemBottom;

          if (onRing || onStem) {
            r = 255;
            g = 182;
            b = 72;
          }

          red += r;
          green += g;
          blue += b;
          covered += 1;
        }
      }

      const total = samples * samples;
      const offset = (y * size + x) * 4;
      if (covered > 0) {
        pixels[offset] = Math.round(red / covered);
        pixels[offset + 1] = Math.round(green / covered);
        pixels[offset + 2] = Math.round(blue / covered);
        pixels[offset + 3] = Math.round((covered / total) * 255);
      }
    }
  }

  return encodePng(size, size, pixels);
}

fs.mkdirSync(OUT_DIR, { recursive: true });
for (const size of SIZES) {
  const file = path.join(OUT_DIR, `icon-${size}.png`);
  fs.writeFileSync(file, render(size));
  console.log(`  ${path.relative(process.cwd(), file)}`);
}
