#!/bin/bash
# 検証用のリポジトリと PR を、初期状態の GitBucket（管理者 root/root）に作る
# 使い方: test/setup.sh <作業ディレクトリ> [GitBucket の URL]
#   PR #1 root/docs   変更・削除・リネーム・コピー元の変更・追加・空ファイル・日本語パス・末尾改行なし
#   PR #2 root/docs   リネームとコピー（削除したファイルと同じ内容を二つ追加）
#   PR #3 root/docs   alice の fork から
#   PR #1 root/secret 非公開リポジトリ（alice は閲覧権限なし）
#   PR #5 root/docs   バイナリとして扱われる Markdown（#4 は通常の issue）
#   PR #6 root/docs   Markdown 以外だけの変更
set -eu
W=$1
B=${2:-http://localhost:18080}
H=${B#http://}
api() { curl -sS -u "$1" -H 'Content-Type: application/json' "${@:2}" >/dev/null; }
api root:root -X POST $B/api/v3/user/repos -d '{"name":"docs"}'
rm -rf $W && mkdir -p $W && cd $W
git init -q -b main && git config user.name root && git config user.email root@localhost
seq 1 30 | sed 's/.*/本文 & 行目。/' > a.md
printf '# 削除される\n\n本文\n' > b.md
printf '# 名前だけ変える\n\n本文\n' > c.md
seq 1 20 | sed 's/.*/リネームして編集 & 行目。/' > d.md
seq 1 20 | sed 's/.*/コピー元 & 行目。/' > e.md
echo text > readme.txt
mkdir '仕様 書' && printf '# 日本語\n\n本文\n' > '仕様 書/x.md'
printf '# 末尾改行なし\n\n最終行' > no-eol.md
printf 'z\n' > z.md
git add -A && git commit -qm init
git checkout -qb feature
sed -i 's/本文 2 行目。/本文 2 行目を変更。/; s/本文 25 行目。/本文 25 行目を変更。/' a.md
git rm -q b.md
git mv c.md c2.md
git mv d.md d2.md && sed -i 's/リネームして編集 3 行目。/変更した 3 行目。/' d2.md
cp e.md e-copy.md && sed -i 's/コピー元 1 行目。/コピー元を変更。/' e.md
echo text2 > readme.txt
printf '# 新規\n\n追加した本文と[外部リンク](https://example.com/)\n' > new.md
: > empty.md
printf '# 日本語\n\n本文を変更\n' > '仕様 書/x.md'
printf '# 末尾改行なし\n\n最終行を変更' > no-eol.md
git add -A && git commit -qm feature
git checkout -q main && printf 'z2\n' > z.md && git commit -qam "main を進める"
git push -q http://root:root@$H/git/root/docs.git main feature
api root:root -X POST $B/api/v3/repos/root/docs/pulls -d '{"title":"検証用 PR","head":"feature","base":"main"}'
# PR #2：コピー（削除したファイルと同じ内容を二つ追加）
git checkout -q main && git checkout -qb copy
git mv a.md a1.md && cp a1.md a2.md && git add -A && git commit -qm copy
git push -q http://root:root@$H/git/root/docs.git copy
api root:root -X POST $B/api/v3/repos/root/docs/pulls -d '{"title":"コピー","head":"copy","base":"main"}'
# 利用者 alice を作り、root/docs を fork して PR を出す
api root:root -X POST $B/api/v3/admin/users -d '{"login":"alice","password":"alice","email":"alice@localhost"}'
curl -sS -c $W/.alice-cookie -o /dev/null $B/signin
curl -sS -b $W/.alice-cookie -c $W/.alice-cookie -o /dev/null -d 'userName=alice&password=alice' $B/signin
curl -sS -b $W/.alice-cookie -o /dev/null -d 'account=alice' $B/root/docs/fork
sleep 2
git checkout -q main && git checkout -qb from-fork && sed -i 's/本文 10 行目。/fork から変更。/' a.md && git commit -qam "fork から"
git push -q http://alice:alice@$H/git/alice/docs.git from-fork
api alice:alice -X POST $B/api/v3/repos/root/docs/pulls -d '{"title":"fork から","head":"alice:from-fork","base":"main"}'
# 非公開リポジトリ root/secret と PR
api root:root -X POST $B/api/v3/user/repos -d '{"name":"secret","private":true}'
git push -q http://root:root@$H/git/root/secret.git main feature
api root:root -X POST $B/api/v3/repos/root/secret/pulls -d '{"title":"非公開","head":"feature","base":"main"}'
# PR #5：バイナリとして扱われる Markdown（#4 は通常の issue）
api root:root -X POST $B/api/v3/repos/root/docs/issues -d '{"title":"ただの issue"}'
git checkout -q main && git checkout -qb bin && printf 'abc\0def\n' > bin.md && git add bin.md && git commit -qm bin
git push -q http://root:root@$H/git/root/docs.git bin
api root:root -X POST $B/api/v3/repos/root/docs/pulls -d '{"title":"バイナリ","head":"bin","base":"main"}'
# PR #6：Markdown 以外だけの変更
git checkout -q main && git checkout -qb text-only && echo text3 > readme.txt && git commit -qam text-only
git push -q http://root:root@$H/git/root/docs.git text-only
api root:root -X POST $B/api/v3/repos/root/docs/pulls -d '{"title":"テキストのみ","head":"text-only","base":"main"}'
