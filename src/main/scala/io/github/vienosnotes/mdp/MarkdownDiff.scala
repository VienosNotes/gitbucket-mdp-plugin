package io.github.vienosnotes.mdp

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.diff.{DiffEntry, DiffFormatter}
import org.eclipse.jgit.lib.{AbbreviatedObjectId, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.json4s.*
import org.json4s.jackson.JsonMethods.{compact, render}

import scala.jdk.CollectionConverters.*
import scala.util.Using

// git-mdp の FileChange（git-mdp/src/changes.ts）と同じ形式の変更ファイル
case class FileChange(status: String, oldPath: Option[String], newPath: Option[String], oldText: String, patch: String)

class MarkdownDiffException(message: String) extends Exception(message)

// 二つのコミットの間の Markdown ファイルの変更を JGit で取得する
object MarkdownDiff {

  private def isMarkdown(path: String): Boolean = {
    val lower = path.toLowerCase
    lower.endsWith(".md") || lower.endsWith(".markdown")
  }

  def collect(git: Git, from: String, to: String): Seq[FileChange] = {
    val repo = git.getRepository
    val entries = Using.resources(new RevWalk(repo), new DiffFormatter(DisabledOutputStream.INSTANCE)) { (walk, df) =>
      df.setRepository(repo)
      // CLI と同じく、類似度 50% 以上をリネームとみなす
      df.setDetectRenames(true)
      df.getRenameDetector.setRenameScore(50)
      df.scan(walk.parseCommit(repo.resolve(from)).getTree, walk.parseCommit(repo.resolve(to)).getTree).asScala.toSeq
    }
    entries
      .filter(e => Seq(e.getOldPath, e.getNewPath).exists(isMarkdown))
      .map { entry =>
        val read = (id: AbbreviatedObjectId) => new String(repo.open(id.toObjectId).getBytes, UTF_8)
        if (entry.getChangeType == ChangeType.COPY) {
          // CLI はコピーを検出しないため、コピーは新規追加として扱う
          FileChange("A", None, Some(entry.getNewPath), "", addPatch(read(entry.getNewId)))
        } else {
          change(repo, entry, read)
        }
      }
  }

  private def change(
    repo: Repository,
    entry: DiffEntry,
    read: AbbreviatedObjectId => String
  ): FileChange = {
    val out = new ByteArrayOutputStream()
    Using.resource(new DiffFormatter(out)) { df =>
      df.setRepository(repo)
      df.setContext(3)
      df.format(entry)
    }
    val patch = new String(out.toByteArray, UTF_8)
    val path = Option(entry.getNewPath).filter(_ != DiffEntry.DEV_NULL).getOrElse(entry.getOldPath)
    if (patch.linesIterator.contains("Binary files differ")) {
      throw new MarkdownDiffException(s"バイナリファイルには対応していません: $path")
    }
    val (status, oldPath, newPath): (String, Option[String], Option[String]) = entry.getChangeType match {
      case ChangeType.ADD    => ("A", None, Some(entry.getNewPath))
      case ChangeType.DELETE => ("D", Some(entry.getOldPath), None)
      case ChangeType.RENAME => ("R", Some(entry.getOldPath), Some(entry.getNewPath))
      case _                 => ("M", Some(entry.getOldPath), Some(entry.getNewPath))
    }
    FileChange(status, oldPath, newPath, oldPath.map(_ => read(entry.getOldId)).getOrElse(""), patch)
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
  def toJson(title: String, files: Seq[FileChange]): String = {
    val path = (p: Option[String]) => p.map(JString(_)).getOrElse(JNull)
    compact(
      render(
        JObject(
          "title" -> JString(title),
          "files" -> JArray(files.toList.map { f =>
            JObject(
              "status" -> JString(f.status),
              "oldPath" -> path(f.oldPath),
              "newPath" -> path(f.newPath),
              "oldText" -> JString(f.oldText),
              "patch" -> JString(f.patch)
            )
          })
        )
      )
    )
  }
}
