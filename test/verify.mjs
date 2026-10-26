// プラグインの JSON から更新後の本文を復元し、Git の内容と照合する
// 使い方: node verify.mjs <JSON> <作業リポジトリ> <比較元> <比較先>
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { buildFileDiff, newText, splitLines } from '../git-mdp/dist/src/diff.js';

const [json, repo, from, to] = process.argv.slice(2);
const data = JSON.parse(readFileSync(json, 'utf8'));
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
const show = (rev, path) => git('show', `${rev}:${path}`);
let ok = true;
for (const f of data.files) {
  const file = buildFileDiff(f.newPath ?? f.oldPath, f.oldText, f.patch);
  const expectedOld = f.oldPath === null ? '' : show(from, f.oldPath);
  const expectedNew = f.newPath === null ? '' : show(to, f.newPath);
  const okOld = f.oldText === expectedOld;
  const okNew = JSON.stringify(splitLines(newText(file.lines))) === JSON.stringify(splitLines(expectedNew));
  if (!okOld || !okNew) ok = false;
  console.log(`${f.status} ${f.oldPath} -> ${f.newPath}: 変更前 ${okOld ? 'OK' : 'NG'} / 更新後 ${okNew ? 'OK' : 'NG'}`);
}
// 対象の一覧を git diff（コピー検出なし、類似度 50%）と比べる
const md = (p) => /\.(md|markdown)$/i.test(p);
const fields = git('-c', 'core.quotePath=false', 'diff', '--name-status', '-z', '-M50%', from, to).split('\0').filter(Boolean);
const expected = [];
for (let i = 0; i < fields.length;) {
  const st = fields[i++][0];
  const a = fields[i++];
  const b = st === 'R' ? fields[i++] : a;
  if (md(a) || md(b)) expected.push(`${st} ${st === 'A' ? null : a} ${st === 'D' ? null : b}`);
}
const actual = data.files.map((f) => `${f.status} ${f.oldPath} ${f.newPath}`);
const same = JSON.stringify([...expected].sort()) === JSON.stringify([...actual].sort());
console.log(`一覧の一致: ${same ? 'OK' : 'NG'}`);
if (!same) console.log({ expected, actual });
process.exit(ok && same ? 0 : 1);
