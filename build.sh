#!/bin/sh
# プラグインの jar を作る。
# git-mdp（サブモジュール）からブラウザ用 JS を生成してリソースに置き、sbt でまとめる
set -eu
cd "$(dirname "$0")"

git submodule update --init
(cd git-mdp && npm ci && npm run build:web)
mkdir -p src/main/resources/mdp/assets
cp git-mdp/dist/web/md-diff.js src/main/resources/mdp/assets/md-diff.js
sbt assembly
ls target/scala-2.13/gitbucket-mdp-plugin-*.jar
