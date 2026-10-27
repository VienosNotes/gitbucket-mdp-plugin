// プラグインの JSON から更新後の本文を復元し、Git の内容と照合する
// 使い方: node verify.mjs <JSON> <作業リポジトリ> <比較元> <比較先> [source]
//   source を指定すると、Markdown 以外のファイル（Source Diff）として照合する
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { buildFileDiff, buildHunkLines, newText, parsePatch, splitLines } from '../git-mdp/dist/src/diff.js';

const [json, repo, from, to, kind] = process.argv.slice(2);
const source = kind === 'source';
const data = JSON.parse(readFileSync(json, 'utf8'));
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
// サブモジュールは git diff と同じく "Subproject commit <ID>" の一行として扱う
const show = (rev, path) => {
  if (path === null) return '';
  const [mode, , id] = git('ls-tree', rev, '--', path).split(/\s+/);
  return mode === '160000' ? `Subproject commit ${id}\n` : git('show', `${rev}:${path}`);
};
// git-mdp の CLI と同じ基準（5,000 行または 512KB を超える）
const large = (text) => splitLines(text).length > 5000 || Buffer.byteLength(text, 'utf8') > 512 * 1024;
let ok = true;
const report = (f, checks) => {
  if (Object.values(checks).includes(false)) ok = false;
  const text = Object.entries(checks).map(([k, v]) => `${k} ${v ? 'OK' : 'NG'}`).join(' / ');
  console.log(`${f.status} ${f.oldPath} -> ${f.newPath}: ${text}`);
};
for (const f of data.files) {
  if (f.binary) {
    // git diff --numstat はバイナリの増減を "-" と表示する
    const numstat = git('diff', '--numstat', '-M50%', from, to, '--', ...[f.oldPath, f.newPath].filter((p) => p !== null));
    report(f, { バイナリ: numstat.startsWith('-\t-\t') });
    continue;
  }
  const expectedOld = show(from, f.oldPath);
  const expectedNew = show(to, f.newPath);
  const expectLarge = source && (large(expectedOld) || large(expectedNew));
  if (f.oldText === null) {
    // hunk だけの表示：各行の本文が、行番号の位置の変更前・変更後の本文と一致する
    const oldLines = splitLines(expectedOld);
    const newLines = splitLines(expectedNew);
    const lines = buildHunkLines(parsePatch(f.patch));
    const match = lines.every((l) => (l.oldNo === null || oldLines[l.oldNo - 1] === l.text) && (l.newNo === null || newLines[l.newNo - 1] === l.text));
    report(f, { 大きすぎる: expectLarge, hunk: match && lines.length > 0 });
    continue;
  }
  const file = buildFileDiff(f.newPath ?? f.oldPath, f.oldText, f.patch);
  const okNew = JSON.stringify(splitLines(newText(file.lines))) === JSON.stringify(splitLines(expectedNew));
  report(f, { 変更前: f.oldText === expectedOld, 更新後: okNew, ...(source ? { 全文表示: !expectLarge } : {}) });
}
// 対象の一覧を git diff（コピー検出なし、類似度 50%）と比べる
const md = (p) => /\.(md|markdown)$/i.test(p);
const fields = git('-c', 'core.quotePath=false', 'diff', '--name-status', '-z', '-M50%', from, to).split('\0').filter(Boolean);
const expected = [];
for (let i = 0; i < fields.length;) {
  const st = fields[i++][0];
  const a = fields[i++];
  const b = st === 'R' ? fields[i++] : a;
  if ((md(a) || md(b)) !== source) expected.push(`${st} ${st === 'A' ? null : a} ${st === 'D' ? null : b}`);
}
const actual = data.files.map((f) => `${f.status} ${f.oldPath} ${f.newPath}`);
const same = JSON.stringify([...expected].sort()) === JSON.stringify([...actual].sort());
console.log(`一覧の一致: ${same ? 'OK' : 'NG'}`);
if (!same) console.log({ expected, actual });
process.exit(ok && same ? 0 : 1);
