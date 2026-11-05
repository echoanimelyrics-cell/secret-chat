// Generates assets/icon.png and assets/adaptive-icon.png (no dependencies).
// Logo: two overlapping chat bubbles (blue + pink) with typing dots.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const N = 1024;

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let k = 0; k < 8; k++) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td), 0);
  return Buffer.concat([len, td, crc]);
}

function encodePng(rgba) {
  const stride = N * 4;
  const raw = Buffer.alloc((stride + 1) * N);
  for (let y = 0; y < N; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(N, 0);
  ihdr.writeUInt32BE(N, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function sdRoundRect(px, py, cx, cy, hx, hy, r) {
  const qx = Math.abs(px - cx) - (hx - r);
  const qy = Math.abs(py - cy) - (hy - r);
  return (
    Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) +
    Math.min(Math.max(qx, qy), 0) -
    r
  );
}

function sdDiamond(px, py, cx, cy, h, r) {
  const dx = px - cx;
  const dy = py - cy;
  const rx = (dx + dy) * Math.SQRT1_2;
  const ry = (dy - dx) * Math.SQRT1_2;
  return sdRoundRect(rx, ry, 0, 0, h, h, r);
}

function sdDots(px, py, cx, cy) {
  let d = 1e9;
  for (const off of [-70, 0, 70]) {
    d = Math.min(d, Math.hypot(px - (cx + off), py - cy) - 24);
  }
  return d;
}

const BLUE = [0x4f, 0x8c, 0xff];
const PINK = [0xff, 0x6f, 0xae];
const WHITE = [255, 255, 255];
const DARK = [0x0b, 0x17, 0x38];

function render(withBg, scale) {
  const out = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      const u = (x - 512) / scale + 512;
      const v = (y - 512) / scale + 512;

      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;

      const over = (col, d, alpha) => {
        const cov = Math.min(1, Math.max(0, 0.5 - d * scale)) * alpha;
        if (cov <= 0) return;
        const ao = cov + a * (1 - cov);
        r = (col[0] * cov + r * a * (1 - cov)) / ao;
        g = (col[1] * cov + g * a * (1 - cov)) / ao;
        b = (col[2] * cov + b * a * (1 - cov)) / ao;
        a = ao;
      };

      if (withBg) {
        const t = y / N;
        r = 0x13 + (0x07 - 0x13) * t;
        g = 0x28 + (0x0f - 0x28) * t;
        b = 0x6b + (0x26 - 0x6b) * t;
        a = 1;
      }

      const dA = Math.min(
        sdRoundRect(u, v, 380, 400, 230, 170, 110),
        sdDiamond(u, v, 310, 565, 48, 10)
      );
      over(BLUE, dA, 1);
      over(WHITE, sdDots(u, v, 380, 400), 0.95);

      const dB = Math.min(
        sdRoundRect(u, v, 640, 610, 230, 170, 110),
        sdDiamond(u, v, 740, 775, 48, 10)
      );
      over(DARK, dB - 18, 1);
      over(PINK, dB, 1);
      over(WHITE, sdDots(u, v, 640, 610), 0.95);

      const i = (y * N + x) * 4;
      out[i] = Math.round(r);
      out[i + 1] = Math.round(g);
      out[i + 2] = Math.round(b);
      out[i + 3] = Math.round(a * 255);
    }
  }
  return out;
}

try {
  const dir = path.join(__dirname, '..', 'assets');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'icon.png'), encodePng(render(true, 1)));
  fs.writeFileSync(
    path.join(dir, 'adaptive-icon.png'),
    encodePng(render(false, 0.85))
  );
  console.log('Icons generated in assets/');
} catch (e) {
  console.log('Icon generation failed:', e && e.message);
}
