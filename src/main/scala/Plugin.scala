import io.github.gitbucket.solidbase.model.Version

class Plugin extends gitbucket.core.plugin.Plugin {
  override val pluginId: String = "mdp"
  override val pluginName: String = "Markdown Diff Plugin"
  override val description: String = "Pull Request の Markdown の差分を、レンダリングした全文で表示する"
  override val versions: List[Version] = List(new Version("0.1.0"))

  // src/main/resources/mdp/assets/ を /plugin-assets/mdp/ で配信する。
  // ブラウザ用 JS（md-diff.js）はビルド時に git-mdp から生成してここに置く
  override val assetsMappings: Seq[(String, String)] = Seq("/mdp" -> "/mdp/assets")
}
