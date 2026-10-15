#!/usr/bin/env node
/*
 * Claude Code status line: model name + context/runtime metrics (no cwd/git).
 * Uses Node (always present alongside Claude Code) — no jq required.
 * Defensive: renders whatever fields exist; never throws.
 */
const fs = require('fs');

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (d) => (raw += d));
process.stdin.on('end', () => {
  let j = {};
  try { j = JSON.parse(raw || '{}'); } catch { j = {}; }

  // Debug: dump the real statusline JSON so we can verify field names.
  // TODO: remove this block once the field schema is confirmed.
  try { fs.writeFileSync(__dirname + '/.statusline-last.json', raw); } catch {}

  const R = '\x1b[0m', B = '\x1b[1m';
  const CY = '\x1b[36m', GR = '\x1b[32m', YE = '\x1b[33m',
        RD = '\x1b[31m', BL = '\x1b[34m', MA = '\x1b[35m';

  const num = (v) => (typeof v === 'number' && isFinite(v) ? v : null);
  const hum = (n) => {
    if (n == null) return '';
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'G';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
    return String(n);
  };
  const pct = (v) => (num(v) == null ? null : Math.round(v));

  const parts = [];

  // --- model name ---
  const model =
    (j.model && (j.model.id || j.model.display_name)) ||
    j.model_name || '';
  if (model) parts.push(`${B}${CY}${model}${R}`);

  // --- context usage % (field name varies; try common shapes) ---
  const cw = j.context_window || j.contextWindow || j.context || {};
  let used = num(cw.used_percentage);
  if (used == null) used = num(cw.used_percent);
  if (used == null && num(cw.total_input_tokens) != null && num(cw.context_limit))
    used = (cw.total_input_tokens / cw.context_limit) * 100;
  const up = pct(used);
  if (up != null) {
    const c = up >= 80 ? RD : up >= 50 ? YE : GR;
    parts.push(`ctx ${c}${up}%${R}`);
  }

  // --- tokens in/out ---
  const tin = num(cw.total_input_tokens);
  const tout = num(cw.total_output_tokens);
  if (tin != null || tout != null) {
    parts.push(`in ${BL}${hum(tin) || '0'}${R} out ${BL}${hum(tout) || '0'}${R}`);
  }

  // --- session duration (from cost block, if present) ---
  const cost = j.cost || {};
  const ms = num(cost.total_duration_ms);
  if (ms != null) {
    const s = Math.round(ms / 1000);
    const dur = s >= 3600 ? `${Math.floor(s / 3600)}h${Math.floor((s % 3600) / 60)}m`
              : s >= 60 ? `${Math.floor(s / 60)}m${s % 60}s`
              : `${s}s`;
    parts.push(`${B}${dur}${R}`);
  }

  // --- rate limits (Claude.ai subscribers only) ---
  const rl = j.rate_limits || {};
  const five = pct(rl.five_hour && rl.five_hour.used_percentage);
  const week = pct(rl.seven_day && rl.seven_day.used_percentage);
  if (five != null || week != null) {
    let seg = `${MA}`;
    if (five != null) seg += `5h:${five}%`;
    if (week != null) seg += `${five != null ? ' ' : ''}7d:${week}%`;
    parts.push(seg + R);
  }

  process.stdout.write(parts.join(' '));
});
