import gitbucket.core.controller.ControllerBase
import io.github.gitbucket.solidbase.model.Version
import io.github.vienosnotes.mdp.MarkdownDiffController

class Plugin extends gitbucket.core.plugin.Plugin {
  override val pluginId: String = "mdp"
  override val pluginName: String = "Markdown Diff Plugin"
  override val description: String = "Pull Request の Markdown の差分を、レンダリングした全文で表示する"
  override val versions: List[Version] = List(new Version("0.1.0"))

  override val controllers: Seq[(String, ControllerBase)] = Seq("/*" -> new MarkdownDiffController())

  // src/main/resources/mdp/assets/ を /plugin-assets/mdp/ で配信する。
  // ブラウザ用 JS（md-diff.js）はビルド時に git-mdp から生成してここに置く
  override val assetsMappings: Seq[(String, String)] = Seq("/mdp" -> "/mdp/assets")

  // PR 画面（Conversation、Commits、Files Changed）のタブ列に「Markdown Diff」を追加する。
  // GitBucket にタブを追加する拡張点がないため、表示後に DOM を書き換える
  override val javaScripts: Seq[(String, String)] = Seq(
    ".*/pull/[0-9]+(/commits|/files)?" ->
      """$(function () {
        |  var tabs = document.getElementById('pullreq-tab');
        |  var base = location.pathname.match(/^(.*\/pull\/[0-9]+)/);
        |  if (!tabs || !base) return;
        |  var link = document.createElement('a');
        |  link.href = base[1] + '/markdown-diff';
        |  link.textContent = 'Markdown Diff';
        |  var item = document.createElement('li');
        |  item.appendChild(link);
        |  tabs.appendChild(item);
        |});""".stripMargin
  )
}
