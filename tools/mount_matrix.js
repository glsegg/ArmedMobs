'use strict';
// Uses the real GeckoLib baker, Molang evaluator, sampler and matrix helpers.
// Usage: node tools/mount_matrix.js [geo.json] [animation.json] [clip] [anchorBone]
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const pwsh = path.join(process.env.ProgramFiles || 'C:/Program Files', 'PowerShell/7/pwsh.exe');
const shell = process.platform === 'win32' ? (fs.existsSync(pwsh) ? pwsh : 'powershell.exe') : 'pwsh';
const result = spawnSync(shell, ['-NoProfile', '-File', path.join(root, 'tools/spike/mount/selftest.ps1'),
  ...process.argv.slice(2)], { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
process.stdout.write(result.stdout || '');
process.stderr.write(result.stderr || '');
if (result.error) throw result.error;
process.exit(result.status === null ? 1 : result.status);
