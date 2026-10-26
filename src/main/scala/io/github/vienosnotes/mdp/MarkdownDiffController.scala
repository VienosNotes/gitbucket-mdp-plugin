package io.github.vienosnotes.mdp

import gitbucket.core.controller.ControllerBase
import gitbucket.core.model.Profile.*
import gitbucket.core.model.Profile.profile.blockingApi.*
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.Directory.getRepositoryDir
import gitbucket.core.util.Implicits.*
import gitbucket.core.util.ReferrerAuthenticator
import org.eclipse.jgit.api.Git
import org.scalatra.UnprocessableEntity

import scala.util.Using

class MarkdownDiffController extends ControllerBase with RepositoryService with AccountService with ReferrerAuthenticator {

  // 閲覧権限は GitBucket 本体の PR 画面と同じ（referrersOnly）
  get("/:owner/:repository/pull/:id/markdown-diff")(referrersOnly { repository =>
    params("id").toIntOpt.flatMap { issueId =>
      for {
        issue <- Issues.filter(_.byPrimaryKey(repository.owner, repository.name, issueId)).firstOption
        pullreq <- PullRequests.filter(_.byPrimaryKey(repository.owner, repository.name, issueId)).firstOption
      } yield {
        try {
          // PR の比較元（分岐点）と比較先は、どちらも base 側のリポジトリにある
          val files = Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
            MarkdownDiff.collect(git, pullreq.commitIdFrom, pullreq.commitIdTo)
          }
          contentType = formats("json")
          MarkdownDiff.toJson(s"${issue.title} #${issue.issueId}", files)
        } catch {
          case e: MarkdownDiffException =>
            contentType = "text/plain; charset=UTF-8"
            UnprocessableEntity(e.getMessage)
        }
      }
    } getOrElse NotFound()
  })
}
