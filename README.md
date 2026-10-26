# gitbucket-mdp-plugin

GitBucket の Pull Request に、Markdown の差分をレンダリングした全文で表示する「Markdown Diff」を追加するプラグイン（開発中）。

表示部分は [git-mdp](https://github.com/VienosNotes/git-mdp)（CLI ツール `git md-diff`）と共用し、git-mdp をサブモジュールとして持つ。計画と進捗は [PLAN-GITBUCKET.md](PLAN-GITBUCKET.md) を参照。

## 必要な環境

- GitBucket 4.48.0
- ビルドする環境：JDK 17 以上、[sbt](https://www.scala-sbt.org/)、Node.js 22 以上、Git

## ビルド

```sh
git clone --recursive https://github.com/VienosNotes/gitbucket-mdp-plugin
cd gitbucket-mdp-plugin
./build.sh
```

`build.sh` は次を行う。

1. サブモジュール（`git-mdp/`）を取得・更新する。
2. git-mdp でブラウザ用 JS（`dist/web/md-diff.js`）を生成し、`src/main/resources/mdp/assets/` にコピーする。
3. `sbt assembly` で jar を作る。生成物は `target/scala-2.13/gitbucket-mdp-plugin-assembly-<版>.jar`。

## 導入

1. 生成した jar を `GITBUCKET_HOME/plugins/`（既定は `~/.gitbucket/plugins/`）に置く。ディレクトリがなければ作る。
2. GitBucket を再起動する。
3. 管理者で「System administration」→「Plugins」を開き、「Markdown Diff Plugin」が表示されることを確認する。

現時点では、ブラウザ用 JS を `<GitBucket の URL>/plugin-assets/mdp/md-diff.js` として配信するだけで、画面には何も追加しない。

## git-mdp の更新を取り込む

```sh
cd git-mdp
git fetch
git checkout <取り込むコミット>
cd ..
git add git-mdp
git commit -m "git-mdp を更新"
./build.sh
```
