# gitbucket-mdp-plugin

GitBucket の Pull Request に、Markdown の差分をレンダリングした全文で表示する「Markdown Diff」を追加するプラグイン。

表示部分は [git-mdp](https://github.com/VienosNotes/git-mdp)（CLI ツール `git md-diff`）と共用し、git-mdp をサブモジュールとして持つ。計画と進捗は [PLAN-GITBUCKET.md](PLAN-GITBUCKET.md) を参照。

## 必要な環境

- GitBucket 4.48
- ビルドする環境：
  - JDK 17 以上
  - sbt（インストール方法は [sbt のダウンロードページ](https://www.scala-sbt.org/download/) を参照）
  - Node.js 22 以上
  - Git

## ビルド

```sh
git clone --recursive https://github.com/VienosNotes/gitbucket-mdp-plugin
cd gitbucket-mdp-plugin
./build.sh
```

`build.sh` は次を行う。

1. サブモジュール（`git-mdp/`）を取得・更新する。
2. git-mdp でブラウザ用 JS（`dist/web/md-diff.js`）を生成し、`src/main/resources/mdp/assets/` にコピーする。
3. `sbt assembly` で jar を作る。生成物は `target/scala-2.13/gitbucket-mdp-plugin-<版>.jar`。

## 導入

1. 生成した jar を `GITBUCKET_HOME/plugins/`（既定は `~/.gitbucket/plugins/`）に置く。ディレクトリがなければ作る。
2. GitBucket を再起動する。
3. 管理者で「System administration」→「Plugins」を開き、「Markdown Diff Plugin」が表示されることを確認する。

## 使い方

Pull Request の画面のタブ列に「Markdown Diff」が追加される。押すと、PR で変更された Markdown ファイルを、レンダリングした全文で表示する画面に移動する。画面の見方は git-mdp の README と同じ。

- 比較対象は Files Changed タブと同じ（PR の分岐点と比較先）。
- 表示内容は sandbox 付きの iframe の中に表示し、GitBucket のログインセッションから切り離している。
- 本文中のリンクは新しいタブで開く。
- Markdown ファイルの変更がない PR では、その旨を表示する。バイナリとして扱われる Markdown ファイルを含む PR はエラーになる。

## 更新

### プラグインを更新する

```sh
git pull
./build.sh   # サブモジュールも、記録されたコミットに合わせて更新される
```

生成した jar を `GITBUCKET_HOME/plugins/` に置き、GitBucket を再起動する。版が変わって jar のファイル名が変わった場合は、古い jar を削除する（同じプラグインが二つ読み込まれるのを避けるため）。

### git-mdp の更新を取り込む

git-mdp で描画処理を変更した場合は、サブモジュールを新しいコミットに付け替えてからビルドする。

```sh
cd git-mdp
git fetch
git checkout <取り込むコミット>   # 通常は origin/main
cd ..
git add git-mdp
git commit -m "git-mdp を更新"
./build.sh
```

### GitBucket を更新したとき

プラグインは `build.sbt` の `gitbucketVersion`（現在は 4.48.0）向けにビルドしている。GitBucket を更新したら、次を確認する。

1. 管理画面のプラグイン一覧に「Markdown Diff Plugin」が表示される（読み込みに失敗した場合は GitBucket のログを確認する）。
2. PR 画面のタブ列に「Markdown Diff」が表示される。
3. 「Markdown Diff」の画面で差分が表示される。

問題があれば、`build.sbt` の `gitbucketVersion` を新しい版に変えてビルドし直す。それでも直らない場合は、下の「既知の制限」に挙げた GitBucket の内部に依存する箇所を確認する。

## 既知の制限

- **タブの追加は GitBucket の画面の構造に依存する。** GitBucket には PR のタブを追加する仕組みがないため、PR 画面のタブ列（`#pullreq-tab`）に JS で追加している。GitBucket の更新で構造が変わると、タブが表示されなくなる。その場合も、`<PR の URL>/markdown-diff` を直接開けば表示できる。
- **PR の情報は GitBucket のテーブルを直接参照している。** PR のタイトルと比較するコミットを、GitBucket の `ISSUE`・`PULL_REQUEST` テーブルから取得している。GitBucket の更新でテーブルの構造が変わると、ビルドや表示に失敗する。
- **描画結果は GitBucket 本体と細部が異なることがある。** 描画には git-mdp と同じ markdown-it を使う。git-mdp の既知の制限（統合表示での表のヘッダーやコードフェンスの変更の崩れなど）も同じく当てはまる。
- **diff のアルゴリズムは JGit の既定に従う。** hunk の切れ目が、git コマンドや CLI の出力と異なる場合がある（どちらも正しい差分）。
- **相対パスの画像やリンクは解決しない。** 本文中の相対パスは表示画面の URL を基準に解決されるため、画像は表示されず、リンクは開けない。

## 動作確認

作業用の GitBucket（初期状態、管理者 root/root）にプラグインを導入して起動したうえで、次を実行する。

```sh
(cd git-mdp && npm run build)       # test/verify.mjs が git-mdp の復元処理を使う
test/setup.sh /tmp/mdp-work         # 検証用のリポジトリと PR を作る
test/check.sh /tmp/mdp-work         # 差分データの取得結果と閲覧権限を確認する
```
