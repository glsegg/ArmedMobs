// Builds a formatted Word document from docs/COMMAND_AND_CONFIG_REFERENCE.md.
//
//   node tools/make_reference_docx.js [--out <file.docx>] [--toc field|static] [--md <file.md>]
//
// No dependencies: the .docx package (OOXML zip) is written by hand, so this runs anywhere Node runs.
// Layout: cover page + table of contents + the reference body. Headings use Word's built-in heading
// styles (so the navigation pane and an auto TOC work), tables get a shaded repeating header row and
// banded body rows, inline `code` is monospaced with a light fill, blockquotes get a left rule.
//
// The body is tables-heavy on purpose (513 table rows in the source), so table column widths are
// computed from the content and the font size steps down as the column count grows.

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
function argOf(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const MD = argOf('--md', path.join(ROOT, 'docs', 'COMMAND_AND_CONFIG_REFERENCE.md'));
const OUT = argOf('--out', path.join(ROOT, 'docs', '指令与配置参考.docx'));
const TOC_MODE = argOf('--toc', 'field'); // field = Word inserts a real TOC with page numbers, static = plain list

// ------------------------------------------------------------------ content geometry
const A4_W = 11906, A4_H = 16838;         // twips
const MARGIN_TB = 1134, MARGIN_LR = 1077; // 2.0 cm / 1.9 cm
const USABLE = A4_W - 2 * MARGIN_LR;      // 9752 twips

const FONT_BODY_LATIN = 'Segoe UI';
const FONT_BODY_CJK = '等线';
const FONT_HEAD_LATIN = 'Segoe UI Semibold';
const FONT_HEAD_CJK = '微软雅黑';
const FONT_MONO = 'Consolas';

const C_TITLE = '1F3864';
const C_H1 = '1F3864';
const C_H2 = '2E5496';
const C_H3 = '1F4E79';
const C_CODE = 'A31515';
const C_QUOTE = '595959';
const C_RULE = 'BFBFBF';
const C_TBL_LINE = '9DB2CC';
const C_TBL_HEAD = 'DCE6F1';
const C_TBL_BAND = 'F5F9FD';
const C_CODE_FILL = 'F4F4F4';

// ------------------------------------------------------------------ tiny helpers
const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&apos;');

const displayWidth = (s) => {
  let w = 0;
  for (const ch of String(s)) {
    const c = ch.codePointAt(0);
    w += (c >= 0x1100 && (c <= 0x115f || (c >= 0x2e80 && c <= 0xa4cf) || (c >= 0xac00 && c <= 0xd7a3)
      || (c >= 0xf900 && c <= 0xfaff) || (c >= 0xfe30 && c <= 0xfe6f) || (c >= 0xff00 && c <= 0xff60)
      || (c >= 0xffe0 && c <= 0xffe6))) ? 2 : 1;
  }
  return w;
};

// ------------------------------------------------------------------ inline markdown -> runs
function parseInline(text, base = {}) {
  const out = [];
  let buf = '';
  const flush = () => { if (buf) { out.push({ text: buf, ...base }); buf = ''; } };
  let i = 0;
  while (i < text.length) {
    if (text.startsWith('**', i)) {
      const end = text.indexOf('**', i + 2);
      if (end > i + 2) {
        // Recurse: `**\`code\`**` is bold code, not literal backticks in a bold run.
        flush();
        for (const inner of parseInline(text.slice(i + 2, end), { ...base, bold: true })) out.push(inner);
        i = end + 2;
        continue;
      }
    }
    if (text[i] === '`') {
      const end = text.indexOf('`', i + 1);
      if (end > i) { flush(); out.push({ text: text.slice(i + 1, end), mono: true, ...base }); i = end + 1; continue; }
    }
    if (text[i] === '*' && text[i + 1] !== '*') {
      const end = text.indexOf('*', i + 1);
      if (end > i + 1) {
        flush();
        for (const inner of parseInline(text.slice(i + 1, end), { ...base, italic: true })) out.push(inner);
        i = end + 1;
        continue;
      }
    }
    if (text[i] === '[') {
      const m = /^\[([^\]]+)\]\(([^)]+)\)/.exec(text.slice(i));
      if (m) { flush(); out.push({ text: m[1], link: m[2] }); i += m[0].length; continue; }
    }
    buf += text[i];
    i += 1;
  }
  flush();
  return out;
}

function runXml(run, base) {
  const props = [];
  const latin = run.mono ? FONT_MONO : (base.head ? FONT_HEAD_LATIN : FONT_BODY_LATIN);
  const cjk = run.mono ? FONT_MONO : (base.head ? FONT_HEAD_CJK : FONT_BODY_CJK);
  props.push(`<w:rFonts w:ascii="${latin}" w:hAnsi="${latin}" w:eastAsia="${cjk}" w:cs="${latin}"/>`);
  if (run.bold || base.bold) props.push('<w:b/><w:bCs/>');
  if (run.italic || base.italic) props.push('<w:i/><w:iCs/>');
  const size = run.mono ? (base.monoSize || base.size) : base.size;
  if (size) props.push(`<w:sz w:val="${size}"/><w:szCs w:val="${size}"/>`);
  const color = base.color || (run.mono ? C_CODE : null);
  if (color) props.push(`<w:color w:val="${color}"/>`);
  if (run.mono) props.push(`<w:shd w:val="clear" w:color="auto" w:fill="${C_CODE_FILL}"/>`);
  if (run.link) props.push('<w:u w:val="single"/>');
  return `<w:r><w:rPr>${props.join('')}</w:rPr><w:t xml:space="preserve">${esc(run.text)}</w:t></w:r>`;
}

function runsXml(runs, base = {}) {
  if (!runs.length) return runXml({ text: '' }, base);
  return runs.map((r) => runXml(r, base)).join('');
}

// ------------------------------------------------------------------ block builders
function paraXml(runs, o = {}) {
  const pPr = [];
  if (o.style) pPr.push(`<w:pStyle w:val="${o.style}"/>`);
  if (o.pageBreakBefore) pPr.push('<w:pageBreakBefore/>');
  if (o.keepNext) pPr.push('<w:keepNext/>');
  if (o.align) pPr.push(`<w:jc w:val="${o.align}"/>`);
  if (o.indent || o.hanging) {
    pPr.push(`<w:ind${o.indent ? ` w:left="${o.indent}"` : ''}${o.hanging ? ` w:hanging="${o.hanging}"` : ''}/>`);
  }
  const spacing = [];
  if (o.before !== undefined) spacing.push(`w:before="${o.before}"`);
  if (o.after !== undefined) spacing.push(`w:after="${o.after}"`);
  if (o.line) spacing.push(`w:line="${o.line}" w:lineRule="auto"`);
  if (spacing.length) pPr.push(`<w:spacing ${spacing.join(' ')}/>`);
  if (o.shading) pPr.push(`<w:shd w:val="clear" w:color="auto" w:fill="${o.shading}"/>`);
  if (o.borderBottom || o.borderLeft) {
    const parts = [];
    if (o.borderBottom) parts.push(`<w:bottom w:val="single" w:sz="6" w:space="1" w:color="${o.borderBottom}"/>`);
    if (o.borderLeft) parts.push(`<w:left w:val="single" w:sz="18" w:space="6" w:color="${o.borderLeft}"/>`);
    pPr.push(`<w:pBdr>${parts.join('')}</w:pBdr>`);
  }
  if (o.bookmark) {
    pPr.push(`<w:rPr><w:vanish/></w:rPr>`);
  }
  const content = (o.bookmark
    ? `<w:bookmarkStart w:id="900" w:name="${o.bookmark}"/><w:bookmarkEnd w:id="900"/>`
    : '') + runsXml(runs, o.run || {});
  return `<w:p>${pPr.length ? `<w:pPr>${pPr.join('')}</w:pPr>` : ''}${content}</w:p>`;
}

function tableXml(rows, opts = {}) {
  const cols = rows[0].length;
  // Column widths from the widest cell (CJK counts double), clamped so no column collapses.
  const weights = new Array(cols).fill(0);
  for (const row of rows) {
    for (let c = 0; c < cols; c++) {
      const w = Math.min(displayWidth(row[c] || ''), 70);
      weights[c] = Math.max(weights[c], w);
    }
  }
  const perCol = [];
  for (let c = 0; c < cols; c++) {
    let sum = 0;
    for (let i = 0; i < cols; i++) sum += i === c ? 0 : 1;
    perCol.push(sum);
  }
  const total = weights.reduce((a, b) => a + b, 0) || cols;
  let widths = weights.map((w) => Math.max(760, Math.round((w / total) * USABLE / 10) * 10));
  let sum = widths.reduce((a, b) => a + b, 0);
  // Fix the rounding drift on the widest column, and only then scale if the minimums overflowed.
  const widest = widths.indexOf(Math.max(...widths));
  widths[widest] += USABLE - sum;
  sum = widths.reduce((a, b) => a + b, 0);
  if (sum > USABLE) {
    const k = USABLE / sum;
    widths = widths.map((w) => Math.round(w * k / 10) * 10);
    widths[widest] += USABLE - widths.reduce((a, b) => a + b, 0);
  }

  const size = cols >= 6 ? 15 : cols >= 5 ? 16 : cols >= 4 ? 16 : 17; // half-points
  const grid = widths.map((w) => `<w:gridCol w:w="${w}"/>`).join('');
  const border = (tag) => `<w:${tag} w:val="single" w:sz="4" w:space="0" w:color="${C_TBL_LINE}"/>`;
  const tblPr = `<w:tblPr><w:tblW w:w="${USABLE}" w:type="dxa"/>`
    + `<w:tblBorders>${['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(border).join('')}</w:tblBorders>`
    + '<w:tblLayout w:type="fixed"/>'
    + '<w:tblCellMar><w:top w:w="28" w:type="dxa"/><w:left w:w="60" w:type="dxa"/>'
    + '<w:bottom w:w="28" w:type="dxa"/><w:right w:w="60" w:type="dxa"/></w:tblCellMar></w:tblPr>';

  const trs = rows.map((row, r) => {
    const isHead = r === 0;
    const fill = isHead ? C_TBL_HEAD : (r % 2 === 0 ? C_TBL_BAND : null);
    const trPr = isHead ? '<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>' : '<w:trPr><w:cantSplit/></w:trPr>';
    const tcs = row.map((cell, c) => {
      const tcPr = `<w:tcPr><w:tcW w:w="${widths[c]}" w:type="dxa"/>`
        + (fill ? `<w:shd w:val="clear" w:color="auto" w:fill="${fill}"/>` : '')
        + (isHead ? '<w:vAlign w:val="center"/>' : '') + '</w:tcPr>';
      const body = paraXml(parseInline(cell || ''), {
        after: 0, before: 0, line: 240,
        run: { size, bold: isHead, color: isHead ? C_H1 : null },
      });
      return `<w:tc>${tcPr}${body}</w:tc>`;
    }).join('');
    return `<w:tr>${trPr}${tcs}</w:tr>`;
  }).join('');
  return `<w:tbl>${tblPr}<w:tblGrid>${grid}</w:tblGrid>${trs}</w:tbl>`
    + paraXml([], { after: 120, line: 240 });
}

// A table row must not be split on a pipe that sits inside a `code span`, and `\|` is a literal pipe
// (the reference uses it to list alternatives such as `a` \| `b` inside one cell).
function splitRow(row) {
  const out = [];
  let buf = '';
  let inCode = false;
  for (let i = 0; i < row.length; i++) {
    const ch = row[i];
    if (ch === '\\' && row[i + 1] === '|') { buf += '|'; i += 1; continue; }
    if (ch === '`') { inCode = !inCode; buf += ch; continue; }
    if (ch === '|' && !inCode) { out.push(buf); buf = ''; continue; }
    buf += ch;
  }
  out.push(buf);
  return out;
}

// ------------------------------------------------------------------ markdown -> blocks
function parseMarkdown(md) {
  const src = md.replace(/\r\n?/g, '\n').split('\n');
  const blocks = [];
  let i = 0;
  let inFence = false;
  let fence = [];
  let para = [];

  const flushPara = () => {
    if (!para.length) return;
    let text = '';
    for (let n = 0; n < para.length; n++) {
      if (n === 0) { text = para[n]; continue; }
      const prev = para[n - 1];
      const a = prev.slice(-1), b = para[n][0] || '';
      const joinSpace = /[A-Za-z0-9)\]]/.test(a) && /[A-Za-z0-9(\[]/.test(b);
      text += (joinSpace ? ' ' : '') + para[n];
    }
    blocks.push({ type: 'p', text });
    para = [];
  };
  const flushFence = () => {
    if (!fence.length) return;
    blocks.push({ type: 'code', lines: fence });
    fence = [];
  };

  while (i < src.length) {
    const line = src[i];
    if (/^```/.test(line.trim())) {
      if (inFence) { flushFence(); inFence = false; } else { flushPara(); inFence = true; }
      i += 1;
      continue;
    }
    if (inFence) { fence.push(line.replace(/\s+$/, '')); i += 1; continue; }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      flushPara();
      blocks.push({ type: 'h', level: heading[1].length, text: heading[2].trim() });
      i += 1;
      continue;
    }
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      flushPara();
      blocks.push({ type: 'hr' });
      i += 1;
      continue;
    }
    if (/^\s*\|/.test(line)) {
      flushPara();
      const rows = [];
      while (i < src.length && /^\s*\|/.test(src[i])) {
        const raw = src[i].trim();
        const isSep = /^\|[\s:|-]+\|$/.test(raw);
        if (!isSep) {
          const cells = splitRow(raw.replace(/^\|/, '').replace(/\|$/, ''));
          rows.push(cells.map((c) => c.trim()));
        }
        i += 1;
      }
      if (rows.length) blocks.push({ type: 'table', rows });
      continue;
    }
    const quote = /^>\s?(.*)$/.exec(line);
    if (quote) {
      flushPara();
      let text = quote[1];
      i += 1;
      while (i < src.length && /^>\s?/.test(src[i])) { text += '\n' + src[i].replace(/^>\s?/, ''); i += 1; }
      blocks.push({ type: 'quote', text });
      continue;
    }
    const bullet = /^(\s*)[-*+]\s+(.*)$/.exec(line);
    if (bullet) {
      flushPara();
      blocks.push({ type: 'li', indent: Math.floor(bullet[1].length / 2), text: bullet[2] });
      i += 1;
      continue;
    }
    const numbered = /^(\s*)(\d+)\.\s+(.*)$/.exec(line);
    if (numbered) {
      flushPara();
      blocks.push({ type: 'oli', indent: Math.floor(numbered[1].length / 2), marker: `${numbered[2]}.`, text: numbered[3] });
      i += 1;
      continue;
    }
    if (!line.trim()) { flushPara(); i += 1; continue; }
    para.push(line.trim());
    i += 1;
  }
  flushPara();
  flushFence();
  return blocks;
}

// ------------------------------------------------------------------ document body
function bodyXml(blocks, meta) {
  const out = [];

  // ---- cover
  out.push(paraXml([{ text: meta.title }], {
    align: 'center', before: 3600, after: 120, line: 240,
    run: { size: 56, bold: true, color: C_TITLE, head: true },
  }));
  out.push(paraXml([{ text: meta.subtitle }], {
    align: 'center', after: 60, line: 240, run: { size: 26, color: C_H2, head: true },
  }));
  out.push(paraXml([{ text: meta.tagline }], {
    align: 'center', after: 400, line: 240, run: { size: 20, color: C_QUOTE },
  }));
  out.push(paraXml([], { align: 'center', borderBottom: C_RULE, after: 320 }));
  out.push(tableXml(meta.info, {}));
  out.push(paraXml(parseInline(meta.note), {
    after: 200, line: 260, indent: 200, borderLeft: C_H2, run: { size: 18, color: C_QUOTE },
  }));
  out.push(paraXml([], { pageBreakBefore: true, after: 0 }));

  // ---- table of contents
  out.push(paraXml([{ text: '目录' }], {
    style: 'Heading1', after: 200, run: { size: 32, bold: true, color: C_H1, head: true },
  }));
  if (meta.toc === 'field') {
    out.push(paraXml([{ text: '（Word 打开时按住 Ctrl 点击条目跳转；页码由 Word 自动生成）' }], {
      after: 160, run: { size: 18, color: C_QUOTE },
    }));
    out.push(paraXml([], { bookmark: 'TOCField', after: 0 }));
  } else {
    for (const b of blocks) {
      if (b.type !== 'h' || b.level < 2 || b.level > 3) continue;
      const text = b.text.replace(/[`*]/g, '');
      out.push(paraXml([{ text }], {
        after: b.level === 2 ? 40 : 0, line: 240,
        indent: b.level === 3 ? 420 : 0,
        run: { size: b.level === 2 ? 20 : 18, bold: b.level === 2, color: b.level === 2 ? C_H1 : C_QUOTE },
      }));
    }
  }
  out.push(paraXml([], { pageBreakBefore: true, after: 0 }));

  // ---- body
  let firstH2 = true;
  for (const b of blocks) {
    if (b.type === 'h') {
      if (b.level === 1) continue; // the title is on the cover
      const run = {
        2: { size: 32, bold: true, color: C_H1, head: true },
        3: { size: 26, bold: true, color: C_H2, head: true },
      }[b.level] || { size: 22, bold: true, color: C_H3, head: true };
      // Headings carry inline code too ("## 2. 服务端命令总表（`/armedmobs ...`）"), so they go through
      // the same inline parser - otherwise the backticks land in the Word text verbatim.
      out.push(paraXml(parseInline(b.text), {
        style: b.level === 2 ? 'Heading1' : b.level === 3 ? 'Heading2' : 'Heading3',
        pageBreakBefore: b.level === 2 && !firstH2,
        keepNext: true,
        before: b.level === 2 ? 320 : 260, after: 140, line: 240,
        run: { ...run, monoSize: run.size - 4 },
      }));
      if (b.level === 2) firstH2 = false;
      continue;
    }
    if (b.type === 'p') {
      out.push(paraXml(parseInline(b.text), { after: 100, line: 288, run: { size: 21 } }));
      continue;
    }
    if (b.type === 'table') {
      out.push(tableXml(b.rows, {}));
      continue;
    }
    if (b.type === 'quote') {
      const lines = b.text.split('\n');
      for (const line of lines) {
        out.push(paraXml(parseInline(line), {
          after: 60, line: 260, indent: 300, borderLeft: C_H2,
          run: { size: 19, color: C_QUOTE, italic: true },
        }));
      }
      continue;
    }
    if (b.type === 'code') {
      for (const line of b.lines) {
        out.push(paraXml([{ text: line }], {
          after: 0, before: 0, line: 240, indent: 300, shading: 'F7F7F7',
          run: { mono: true, size: 17, monoSize: 17, color: '333333' },
        }));
      }
      out.push(paraXml([], { after: 80, line: 240 }));
      continue;
    }
    if (b.type === 'li' || b.type === 'oli') {
      const indent = 300 + b.indent * 300;
      const marker = b.type === 'li' ? (b.indent === 0 ? '•' : '◦') : b.marker;
      const runs = [{ text: `${marker} ` }, ...parseInline(b.text)];
      out.push(paraXml(runs, {
        after: 40, line: 264, indent, hanging: 240, run: { size: 21 },
      }));
      continue;
    }
    if (b.type === 'hr') {
      out.push(paraXml([], { after: 140, borderBottom: C_RULE }));
    }
  }
  return out.join('');
}

// ------------------------------------------------------------------ OOXML parts
const W_NS = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
  + 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"';

function stylesXml() {
  const heading = (id, name, lvl, size, color) => `
  <w:style w:type="paragraph" w:styleId="${id}"><w:name w:val="${name}"/><w:basedOn w:val="Normal"/>
    <w:qFormat/>
    <w:pPr><w:keepNext/><w:keepLines/><w:outlineLvl w:val="${lvl}"/>
      <w:spacing w:before="280" w:after="120" w:line="240" w:lineRule="auto"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="${FONT_HEAD_LATIN}" w:hAnsi="${FONT_HEAD_LATIN}" w:eastAsia="${FONT_HEAD_CJK}"/>
      <w:b/><w:color w:val="${color}"/><w:sz w:val="${size}"/><w:szCs w:val="${size}"/></w:rPr></w:style>`;
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles ${W_NS}>
  <w:docDefaults>
    <w:rPrDefault><w:rPr>
      <w:rFonts w:ascii="${FONT_BODY_LATIN}" w:hAnsi="${FONT_BODY_LATIN}" w:eastAsia="${FONT_BODY_CJK}" w:cs="${FONT_BODY_LATIN}"/>
      <w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/>
    </w:rPr></w:rPrDefault>
    <w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="288" w:lineRule="auto"/></w:pPr></w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
  <w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont"><w:name w:val="Default Paragraph Font"/></w:style>
  <w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/>
    <w:tblPr><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="60" w:type="dxa"/>
      <w:bottom w:w="0" w:type="dxa"/><w:right w:w="60" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:outlineLvl w:val="0"/><w:spacing w:after="120"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="${FONT_HEAD_LATIN}" w:hAnsi="${FONT_HEAD_LATIN}" w:eastAsia="${FONT_HEAD_CJK}"/>
      <w:b/><w:color w:val="${C_TITLE}"/><w:sz w:val="56"/></w:rPr></w:style>${heading('Heading1', 'heading 1', 0, 32, C_H1)}${heading('Heading2', 'heading 2', 1, 26, C_H2)}${heading('Heading3', 'heading 3', 2, 22, C_H3)}${heading('Heading4', 'heading 4', 3, 21, '404040')}
</w:styles>`;
}

function headerXml() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr ${W_NS}><w:p><w:pPr><w:jc w:val="right"/>
  <w:pBdr><w:bottom w:val="single" w:sz="4" w:space="2" w:color="${C_RULE}"/></w:pBdr>
  <w:spacing w:after="60"/></w:pPr>
  <w:r><w:rPr><w:rFonts w:ascii="${FONT_BODY_LATIN}" w:hAnsi="${FONT_BODY_LATIN}" w:eastAsia="${FONT_BODY_CJK}"/>
    <w:color w:val="${C_QUOTE}"/><w:sz w:val="17"/></w:rPr>
    <w:t xml:space="preserve">Armed Mobs（武装暴徒）· 指令 / 配置 / 第三方内容参考</w:t></w:r></w:p></w:hdr>`;
}

function footerXml() {
  const r = (props, text) => `<w:r><w:rPr><w:rFonts w:ascii="${FONT_BODY_LATIN}" w:hAnsi="${FONT_BODY_LATIN}" w:eastAsia="${FONT_BODY_CJK}"/><w:color w:val="${C_QUOTE}"/><w:sz w:val="17"/>${props}</w:rPr><w:t xml:space="preserve">${text}</w:t></w:r>`;
  const field = (instr, placeholder) => `<w:r><w:rPr><w:sz w:val="17"/><w:color w:val="${C_QUOTE}"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r>`
    + `<w:r><w:rPr><w:sz w:val="17"/></w:rPr><w:instrText xml:space="preserve"> ${instr} </w:instrText></w:r>`
    + `<w:r><w:rPr><w:sz w:val="17"/></w:rPr><w:fldChar w:fldCharType="separate"/></w:r>`
    + r('', placeholder)
    + '<w:r><w:rPr><w:sz w:val="17"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r>';
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr ${W_NS}><w:p><w:pPr><w:jc w:val="center"/><w:spacing w:before="60"/></w:pPr>
  ${r('', '第 ')}${field('PAGE', '1')}${r('', ' 页 / 共 ')}${field('NUMPAGES', '1')}${r('', ' 页')}
</w:p></w:ftr>`;
}

function documentXml(body) {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document ${W_NS}><w:body>${body}
<w:sectPr>
  <w:headerReference w:type="default" r:id="rId3"/><w:footerReference w:type="default" r:id="rId4"/>
  <w:pgSz w:w="${A4_W}" w:h="${A4_H}"/>
  <w:pgMar w:top="${MARGIN_TB}" w:right="${MARGIN_LR}" w:bottom="${MARGIN_TB}" w:left="${MARGIN_LR}"
           w:header="600" w:footer="600" w:gutter="0"/>
  <w:cols w:space="720"/><w:docGrid w:linePitch="312"/>
</w:sectPr></w:body></w:document>`;
}

const REL_NS = 'xmlns="http://schemas.openxmlformats.org/package/2006/relationships"';
const OFFICE = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';

function contentTypesXml() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>`;
}

function rootRelsXml() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships ${REL_NS}>
  <Relationship Id="rId1" Type="${OFFICE}/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="${OFFICE}/extended-properties" Target="docProps/app.xml"/>
</Relationships>`;
}

function documentRelsXml() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships ${REL_NS}>
  <Relationship Id="rId1" Type="${OFFICE}/styles" Target="styles.xml"/>
  <Relationship Id="rId2" Type="${OFFICE}/settings" Target="settings.xml"/>
  <Relationship Id="rId3" Type="${OFFICE}/header" Target="header1.xml"/>
  <Relationship Id="rId4" Type="${OFFICE}/footer" Target="footer1.xml"/>
</Relationships>`;
}

function settingsXml() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings ${W_NS}>
  <w:zoom w:percent="100"/>
  <w:defaultTabStop w:val="420"/>
  <w:characterSpacingControl w:val="compressPunctuation"/>
  <w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>
  <w:themeFontLang w:val="en-US" w:eastAsia="zh-CN"/>
</w:settings>`;
}

function coreXml(meta) {
  const iso = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
  xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
  xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>${esc(meta.title)}</dc:title>
  <dc:subject>${esc(meta.subtitle)}</dc:subject>
  <dc:creator>Armed Mobs</dc:creator>
  <cp:lastModifiedBy>Armed Mobs</cp:lastModifiedBy>
  <dcterms:created xsi:type="dcterms:W3CDTF">${iso}</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">${iso}</dcterms:modified>
</cp:coreProperties>`;
}

function appXml(meta) {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
  xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
  <Application>Armed Mobs reference builder</Application>
  <Company></Company>
  <DocSecurity>0</DocSecurity>
  <ScaleCrop>false</ScaleCrop>
  <SharedDoc>false</SharedDoc>
  <HyperlinksChanged>false</HyperlinksChanged>
  <AppVersion>1.0</AppVersion>
</Properties>`;
}

// ------------------------------------------------------------------ minimal zip writer
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function zip(files) {
  const now = new Date();
  const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | Math.floor(now.getSeconds() / 2);
  const dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
  const parts = [];
  const central = [];
  let offset = 0;
  for (const file of files) {
    const nameBuf = Buffer.from(file.name, 'utf8');
    const data = Buffer.isBuffer(file.data) ? file.data : Buffer.from(file.data, 'utf8');
    const comp = zlib.deflateRawSync(data, { level: 9 });
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(comp.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    parts.push(local, nameBuf, comp);
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(8, 10);
    cd.writeUInt16LE(dosTime, 12);
    cd.writeUInt16LE(dosDate, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(comp.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);
    cd.writeUInt16LE(0, 32);
    cd.writeUInt16LE(0, 34);
    cd.writeUInt16LE(0, 36);
    cd.writeUInt32LE(0, 38);
    cd.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([cd, nameBuf]));
    offset += local.length + nameBuf.length + comp.length;
  }
  const centralBuf = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...parts, centralBuf, end]);
}

// ------------------------------------------------------------------ main
function main() {
  const md = fs.readFileSync(MD, 'utf8');
  const blocks = parseMarkdown(md);
  const counts = {
    headings: blocks.filter((b) => b.type === 'h').length,
    tables: blocks.filter((b) => b.type === 'table').length,
    tableRows: blocks.filter((b) => b.type === 'table').reduce((a, b) => a + b.rows.length, 0),
    paragraphs: blocks.filter((b) => b.type === 'p').length,
    bullets: blocks.filter((b) => b.type === 'li' || b.type === 'oli').length,
    quotes: blocks.filter((b) => b.type === 'quote').length,
    codeLines: blocks.filter((b) => b.type === 'code').reduce((a, b) => a + b.lines.length, 0),
  };

  const today = new Date();
  const stamp = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  const meta = {
    title: 'Armed Mobs 指令 / 配置 / 第三方内容参考',
    subtitle: 'Minecraft 1.20.1 · Forge 47 · TaCZ 1.1.7+ · modId tarkovscav',
    tagline: '命令 · 配置键 · AI 档位 · 数据包与第三方内容接口',
    toc: TOC_MODE,
    info: [
      ['项', '值'],
      ['显示名 / 内部 id', 'Armed Mobs（武装暴徒） / tarkovscav（**不变**，存档与配置兼容）'],
      ['构建产物', 'armedmobs-0.1.0-all.jar'],
      ['主命令根 / 别名', '`/armedmobs ...` / `/tarkovscav ...`（同一棵树注册两次）'],
      ['权限', '服务端命令需权限等级 2；客户端命令无权限要求'],
      ['配置文件', '`config/tarkovscav-common.toml`'],
      ['本文依据', '源码逐条核对：`ModCommands` / `ClientCommands` / `Config` / `AiProfile` / `registry/*` / `world/*` / `data/tarkovscav/**`'],
      ['校验方式', '`node tools/selftest_wiki_doc.js`（少一条命令或一个配置键即报红）'],
      ['文档版本', stamp],
    ],
    note: '说明：所有命令、参数、默认值与键名都逐条取自源码。凡无法从源码确证的内容，正文中一律标注 **（未核对）** 而不做猜测；'
      + '第 9.1 节汇总了这些条目，第 9.2 节记录了本文撰写时发现并已修复的文档漂移。',
  };

  const body = bodyXml(blocks, meta);
  // Self-check: a backtick or a bold marker inside the rendered text means the inline parser missed a
  // span, and Word would show the raw markdown. `**` is allowed only where the source means it
  // literally (a glob such as {structure,structure_set,template_pool}/** inside a code span).
  const leftoverTicks = (body.match(/`/g) || []).length;
  const leftoverBold = (body.match(/\*\*/g) || []).length;
  const files = [
    { name: '[Content_Types].xml', data: contentTypesXml() },
    { name: '_rels/.rels', data: rootRelsXml() },
    { name: 'word/document.xml', data: documentXml(body) },
    { name: 'word/_rels/document.xml.rels', data: documentRelsXml() },
    { name: 'word/styles.xml', data: stylesXml() },
    { name: 'word/settings.xml', data: settingsXml() },
    { name: 'word/header1.xml', data: headerXml() },
    { name: 'word/footer1.xml', data: footerXml() },
    { name: 'docProps/core.xml', data: coreXml(meta) },
    { name: 'docProps/app.xml', data: appXml(meta) },
  ];

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, zip(files));

  console.log(`markdown : ${path.relative(ROOT, MD)} (${md.length} chars)`);
  console.log(`docx     : ${path.relative(ROOT, OUT)} (${fs.statSync(OUT).size} bytes)`);
  console.log(`toc mode : ${TOC_MODE}`);
  console.log(`blocks   : ${counts.headings} heading(s), ${counts.tables} table(s) / ${counts.tableRows} row(s), `
    + `${counts.paragraphs} paragraph(s), ${counts.bullets} list item(s), ${counts.quotes} quote(s), ${counts.codeLines} code line(s)`);
  console.log(`raw markers: backticks in output = ${leftoverTicks} (want 0), '**' = ${leftoverBold} (globs in code spans only)`);
  if (leftoverTicks > 0) {
    const at = body.indexOf('`');
    console.log(`  first leftover backtick context: ${body.slice(Math.max(0, at - 200), at + 80).replace(/\s+/g, ' ')}`);
  }
}

main();
