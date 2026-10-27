package io.github.vienosnotes.mdp

import gitbucket.core.controller.ControllerBase
import gitbucket.core.model.Profile.*
import gitbucket.core.model.Profile.profile.blockingApi.*
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.util.Directory.getRepositoryDir
import gitbucket.core.util.Implicits.*
import gitbucket.core.util.ReferrerAuthenticator
import gitbucket.core.util.StringUtil.escapeHtml
import org.eclipse.jgit.api.Git
import org.scalatra.UnprocessableEntity

import scala.util.Using

class MarkdownDiffController extends ControllerBase with RepositoryService with AccountService with ReferrerAuthenticator {

  // 閲覧権限は GitBucket 本体の PR 画面と同じ（referrersOnly）
  get("/:owner/:repository/pull/:id/markdown-diff")(referrersOnly(show(_, source = false)))

  // Markdown 以外のファイル（Markdown Diff の対象外のすべて）
  get("/:owner/:repository/pull/:id/source-diff")(referrersOnly(show(_, source = true)))

  private def show(repository: RepositoryInfo, source: Boolean): Any =
    params("id").toIntOpt.flatMap { issueId =>
      for {
        issue <- Issues.filter(_.byPrimaryKey(repository.owner, repository.name, issueId)).firstOption
        pullreq <- PullRequests.filter(_.byPrimaryKey(repository.owner, repository.name, issueId)).firstOption
      } yield {
        contentType = "text/html; charset=UTF-8"
        val title = s"${issue.title} #${issue.issueId}"
        val label = if (source) "Source Diff" else "Markdown Diff"
        val prUrl = s"${context.path}/${repository.owner}/${repository.name}/pull/${issue.issueId}"
        try {
          // PR の比較元（分岐点）と比較先は、どちらも base 側のリポジトリにある
          val files = Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
            MarkdownDiff.collect(git, pullreq.commitIdFrom, pullreq.commitIdTo, source)
          }
          val kind = if (source) "source" else "markdown"
          if (files.isEmpty) page(label, title, prUrl, message(s"${if (source) "Markdown 以外の" else "Markdown "}ファイルの変更はありません。"))
          else page(label, title, prUrl, viewer(label, MarkdownDiff.toJson(title, kind, files)))
        } catch {
          case e: MarkdownDiffException => UnprocessableEntity(page(label, title, prUrl, message(e.getMessage)))
        }
      }
    } getOrElse NotFound()

  private def message(text: String): String = s"""<p class="message">${escapeHtml(text)}</p>"""

  // 描画結果は sandbox 付きの iframe に表示し、GitBucket のオリジンから切り離す。
  // 外部リンクは新しいタブで開けるよう、ポップアップだけを許可する
  private def viewer(label: String, json: String): String =
    s"""<iframe id="md-diff" sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox" title="$label"></iframe>
<script type="application/json" id="md-diff-input">${json.replace("<", "\\u003c")}</script>
<script src="${context.path}/plugin-assets/mdp/md-diff.js"></script>
<script>
  const data = JSON.parse(document.getElementById('md-diff-input').textContent);
  // 本文中のリンクは新しいタブで開く（ページ内リンクは git-mdp の JS が処理する）
  const html = MdDiff.renderMarkdownDiff(data).replace('<head>', '<head><base target="_blank">');
  document.getElementById('md-diff').srcdoc = html;
</script>"""

  private def page(label: String, title: String, prUrl: String, body: String): String =
    s"""<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)} - $label</title>
<style>
html, body { margin: 0; height: 100%; }
body { display: flex; flex-direction: column; font-family: -apple-system, "Segoe UI", "Hiragino Sans", "Noto Sans JP", sans-serif; }
header { display: flex; gap: 16px; align-items: baseline; padding: 8px 16px; border-bottom: 1px solid #d1d9e0; font-size: 14px; }
header .title { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
iframe { flex: 1; width: 100%; border: 0; }
.message { padding: 16px; }
</style>
</head>
<body>
<header><a href="${escapeHtml(prUrl)}">← Pull Request に戻る</a><span class="title">${escapeHtml(title)}</span></header>
$body
</body>
</html>
"""
}
