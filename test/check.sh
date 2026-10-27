#!/bin/bash
# test/setup.sh で作った PR について、差分データの取得結果と閲覧権限を確認する
# 使い方: test/check.sh <作業ディレクトリ> [GitBucket の URL]
# 事前に git-mdp/ で npm run build を実行しておく（test/verify.mjs が git-mdp の復元処理を使う）
set -u
W=$1
B=${2:-http://localhost:18080}
T=$(cd "$(dirname "$0")" && pwd)
fail=0
signin() { curl -sS -c "$W/.$1" -o /dev/null $B/signin; curl -sS -b "$W/.$1" -c "$W/.$1" -o /dev/null -d "userName=$1&password=$1" $B/signin; }
code() { curl -sS -o /dev/null -w "%{http_code}" "$@"; }
expect() { if [ "$2" = "$3" ]; then echo "OK  $1"; else echo "NG  $1（期待 $3、結果 $2）"; fail=1; fi; }
signin root; signin alice
cd "$W"
# 引数：リポジトリ PR 番号 比較先のブランチ 表示（markdown-diff / source-diff）
for pr in "docs 1 feature markdown-diff" "docs 2 copy markdown-diff" "docs 3 from-fork markdown-diff" "secret 1 feature markdown-diff" \
  "docs 1 feature source-diff" "docs 7 source source-diff" "docs 7 source markdown-diff" "docs 8 submodule source-diff"; do
  set -- $pr
  # 表示画面に埋め込んだ JSON を取り出す
  curl -sS -b "$W/.root" "$B/root/$1/pull/$2/$4" |
    node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(/id="md-diff-input">(.*?)<\/script>/s.exec(s)[1]))' > "$W/.pr.json"
  echo "--- root/$1 PR #$2 $4"
  # PR の比較元は、PR を作成した時点の分岐点（main を進めた後も変わらない）
  node "$T/verify.mjs" "$W/.pr.json" "$W" "$(git merge-base "$3" main)" "$3" $([ "$4" = source-diff ] && echo source) || fail=1
done
expect "非公開：root" "$(code -b "$W/.root" $B/root/secret/pull/1/markdown-diff)" 200
expect "非公開：閲覧権限のない alice" "$(code -b "$W/.alice" $B/root/secret/pull/1/markdown-diff)" 401
expect "非公開：未ログイン" "$(code $B/root/secret/pull/1/markdown-diff)" 401
expect "公開：未ログイン" "$(code $B/root/docs/pull/1/markdown-diff)" 200
expect "PR ではない issue" "$(code -b "$W/.root" $B/root/docs/pull/4/markdown-diff)" 404
expect "存在しない番号" "$(code -b "$W/.root" $B/root/docs/pull/99/markdown-diff)" 404
expect "バイナリを含む PR" "$(code -b "$W/.root" $B/root/docs/pull/5/markdown-diff)" 422
expect "Markdown の変更がない PR" "$(curl -sS -b "$W/.root" $B/root/docs/pull/6/markdown-diff | grep -c 'Markdown ファイルの変更はありません')" 1
expect "Source Diff：Markdown 以外の変更がない PR" "$(curl -sS -b "$W/.root" $B/root/docs/pull/5/source-diff | grep -c 'Markdown 以外のファイルの変更はありません')" 1
expect "Source Diff：非公開・閲覧権限のない alice" "$(code -b "$W/.alice" $B/root/secret/pull/1/source-diff)" 401
expect "Source Diff：PR ではない issue" "$(code -b "$W/.root" $B/root/docs/pull/4/source-diff)" 404
exit $fail
