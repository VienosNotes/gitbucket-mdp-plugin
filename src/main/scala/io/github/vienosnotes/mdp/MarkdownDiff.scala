package io.github.vienosnotes.mdp

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.diff.{DiffEntry, DiffFormatter}
import org.eclipse.jgit.lib.{AbbreviatedObjectId, FileMode, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.json4s.*
import org.json4s.jackson.JsonMethods.{compact, render}

import scala.jdk.CollectionConverters.*
import scala.util.Using

// git-mdp の FileChange（git-mdp/src/changes.ts）と同じ形式の変更ファイル。
// oldText が None のときは大きすぎるため hunk だけを表示する
case class FileChange(
  status: String,
  oldPath: Option[String],
  newPath: Option[String],
  oldText: Option[String],
  patch: String,
  binary: Boolean = false
)

class MarkdownDiffException(message: String) extends Exception(message)

// 二つのコミットの間の Markdown ファイル（source = true ではそれ以外のファイル）の変更を JGit で取得する
object MarkdownDiff {

  // ソースの表示で、全文を表示せず hunk だけを表示するファイルの大きさ（git-mdp の CLI と同じ基準）
  private val LargeLines = 5000
  private val LargeBytes = 512 * 1024

  private def isMarkdown(path: String): Boolean = {
    val lower = path.toLowerCase
    lower.endsWith(".md") || lower.endsWith(".markdown")
  }

  def collect(git: Git, from: String, to: String, source: Boolean = false): Seq[FileChange] = {
    val repo = git.getRepository
    val entries = Using.resources(new RevWalk(repo), new DiffFormatter(DisabledOutputStream.INSTANCE)) { (walk, df) =>
      df.setRepository(repo)
      // CLI と同じく、類似度 50% 以上をリネームとみなす
      df.setDetectRenames(true)
      df.getRenameDetector.setRenameScore(50)
      df.scan(walk.parseCommit(repo.resolve(from)).getTree, walk.parseCommit(repo.resolve(to)).getTree).asScala.toSeq
    }
    // Markdown は変更前後のどちらかが Markdown なら対象。ソースはそれ以外のすべて
    val markdown = (e: DiffEntry) => Seq(e.getOldPath, e.getNewPath).exists(isMarkdown)
    entries
      .filter(e => markdown(e) != source)
      .map { entry =>
        val read = (id: AbbreviatedObjectId) => new String(repo.open(id.toObjectId).getBytes, UTF_8)
        if (entry.getChangeType == ChangeType.COPY) {
          // CLI はコピーを検出しないため、コピーは新規追加として扱う
          val patch = addPatch(read(entry.getNewId))
          FileChange("A", None, Some(entry.getNewPath), if (source && isLarge("", patch)) None else Some(""), patch)
        } else {
          change(repo, entry, read, source)
        }
      }
  }

  private def change(
    repo: Repository,
    entry: DiffEntry,
    read: AbbreviatedObjectId => String,
    source: Boolean
  ): FileChange = {
    val out = new ByteArrayOutputStream()
    Using.resource(new DiffFormatter(out)) { df =>
      df.setRepository(repo)
      df.setContext(3)
      df.format(entry)
    }
    val patch = new String(out.toByteArray, UTF_8)
    val path = Option(entry.getNewPath).filter(_ != DiffEntry.DEV_NULL).getOrElse(entry.getOldPath)
    val (status, oldPath, newPath): (String, Option[String], Option[String]) = entry.getChangeType match {
      case ChangeType.ADD    => ("A", None, Some(entry.getNewPath))
      case ChangeType.DELETE => ("D", Some(entry.getOldPath), None)
      case ChangeType.RENAME => ("R", Some(entry.getOldPath), Some(entry.getNewPath))
      case _                 => ("M", Some(entry.getOldPath), Some(entry.getNewPath))
    }
    if (entry.getOldMode == FileMode.GITLINK || entry.getNewMode == FileMode.GITLINK) {
      // サブモジュールの本文（コミット）はこのリポジトリにないため、git diff と同じく
      // "Subproject commit <ID>" の一行を変更前の本文とする
      val oldText = if (entry.getOldMode == FileMode.GITLINK) s"Subproject commit ${entry.getOldId.toObjectId.name}\n" else ""
      return FileChange(status, oldPath, newPath, Some(oldText), patch)
    }
    if (patch.linesIterator.contains("Binary files differ")) {
      // ソースではバイナリも珍しくないため、エラーにせず状態だけを表示する
      if (source) return FileChange(status, oldPath, newPath, Some(""), "", binary = true)
      throw new MarkdownDiffException(s"バイナリファイルには対応していません: $path")
    }
    if (!source) return FileChange(status, oldPath, newPath, Some(oldPath.map(_ => read(entry.getOldId)).getOrElse("")), patch)
    // 大きすぎる変更前の本文は読み込まない
    if (oldPath.isDefined && repo.open(entry.getOldId.toObjectId).getSize > LargeBytes) {
      return FileChange(status, oldPath, newPath, None, patch)
    }
    val oldText = oldPath.map(_ => read(entry.getOldId)).getOrElse("")
    FileChange(status, oldPath, newPath, if (isLarge(oldText, patch)) None else Some(oldText), patch)
  }

  // 変更前または変更後が大きすぎるか。変更後の大きさは、変更前の本文とパッチの増減から求める
  private def isLarge(oldText: String, patch: String): Boolean = {
    val size = (line: String) => line.getBytes(UTF_8).length // 先頭の +/- の 1 バイトを改行の分とみなす
    var lines = if (oldText.isEmpty) 0 else oldText.split("\n", -1).length - (if (oldText.endsWith("\n")) 1 else 0)
    var bytes = oldText.getBytes(UTF_8).length.toLong
    if (lines > LargeLines || bytes > LargeBytes) return true
    // hunk ヘッダーより前の "--- a/..." などはパッチの増減に含めない
    patch.linesIterator.dropWhile(!_.startsWith("@@")).foreach { line =>
      if (line.startsWith("+")) { lines += 1; bytes += size(line) }
      else if (line.startsWith("-")) { lines -= 1; bytes -= size(line) }
    }
    lines > LargeLines || bytes > LargeBytes
  }

  // 新規追加の unified diff（hunk のみ）。git-mdp は hunk ヘッダーより前の行を読み飛ばす
  private def addPatch(text: String): String = {
    if (text.isEmpty) return ""
    val lines = text.split("\n", -1).toList
    val body = if (text.endsWith("\n")) lines.init else lines
    val noNewline = if (text.endsWith("\n")) Nil else List("\\ No newline at end of file")
    (s"@@ -0,0 +1,${body.size} @@" :: body.map("+" + _) ::: noNewline).mkString("", "\n", "\n")
  }

  // git-mdp の DiffData（git-mdp/src/web.ts）の JSON。パスがない場合は null を出力する
  def toJson(title: String, kind: String, files: Seq[FileChange]): String = {
    val path = (p: Option[String]) => p.map(JString(_)).getOrElse(JNull)
    compact(
      render(
        JObject(
          "title" -> JString(title),
          "kind" -> JString(kind),
          "files" -> JArray(files.toList.map { f =>
            JObject(
              "status" -> JString(f.status),
              "oldPath" -> path(f.oldPath),
              "newPath" -> path(f.newPath),
              "oldText" -> f.oldText.map(JString(_)).getOrElse(JNull),
              "patch" -> JString(f.patch),
              "binary" -> JBool(f.binary)
            )
          })
        )
      )
    )
  }
}
