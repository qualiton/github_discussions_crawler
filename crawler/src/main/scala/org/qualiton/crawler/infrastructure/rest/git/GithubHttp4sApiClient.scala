package org.qualiton.crawler
package infrastructure.rest.git

import java.time.Instant
import java.util.Base64

import scala.concurrent.ExecutionContext

import cats.effect.{ Concurrent, ConcurrentEffect, Timer }
import fs2.Stream

import eu.timepit.refined.auto.autoUnwrap
import io.chrisdavenport.log4cats.Logger
import io.circe.Decoder
import io.circe.fs2._
import cats.implicits._

import io.circe.generic.auto._
import org.http4s.{ AuthScheme, Credentials, Header, Headers, Method, Request, Response, Uri }
import org.http4s.syntax.string._
import org.http4s.MediaType.application.json
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.middleware.{ Retry, RetryPolicy }
import org.http4s.headers.{ Accept, Authorization, Link }

import org.qualiton.crawler.common.config.GitConfig
import org.qualiton.crawler.domain.git._
import org.qualiton.crawler.infrastructure.rest.git.GithubHttp4sApiClient.{ TeamDiscussionCommentEntity, TeamDiscussionCommentsResponse, TeamDiscussionResponse, UserTeamResponse }

class GithubHttp4sApiClient[F[_] : Concurrent : Logger] private(
    client: Client[F],
    gitConfig: GitConfig) extends GithubApiClient[F] with Http4sClientDsl[F] {

  import gitConfig._

  private val authorization = Authorization(Credentials.Token(AuthScheme.Basic, Base64.getEncoder.encodeToString(s"${ apiToken.value.value }:x-oauth-basic".getBytes)))
  private val previewAcceptHeader = Header("Accept", "application/vnd.github.echo-preview+json")

  private def prepareRequest(path: String, acceptHeader: Header): Request[F] =
    prepareRequest(
      Uri.unsafeFromString(baseUrl)
        .withPath(path)
        .withQueryParam("direction", "desc")
        .withQueryParam("per_page", "50"), acceptHeader)

  private def prepareRequest(uri: Uri, acceptHeader: Header): Request[F] =
    Request[F](
      method = Method.GET,
      uri = uri,
      headers = Headers(authorization, acceptHeader))

  private def sendReceiveStream[A: Decoder](request: Request[F]): Stream[F, A] = {

    def extractNextUri(response: Response[F]): Option[Uri] =
      response.headers.get("link".ci)
        .flatMap {
          _.value.split(",").toList
            .flatMap(i => Link.parse(i.trim).toOption)
            .find(_.rel == Some("next"))
            .map(_.uri)
        }

    def extractCurrentRequestAcceptHeader(request: Request[F]): Header =
      request.headers.get("accept".ci).getOrElse(Accept(json))

    def decodedResponseStream(response: Response[F]): Stream[F, A] =
      response.bodyAsText.through(stringArrayParser).evalTap(json => Logger[F].debug(s"response value: $json")).through(decoder[F, A])

    def followNextLink(maybeNextUri: Option[Uri], acceptHeader: Header): Stream[F, A] =
      maybeNextUri.fold[Stream[F, A]](Stream.empty.covary)(uri => sendReceiveStream[A](prepareRequest(uri, acceptHeader)))

    for {
      response <- client.stream(request)
      maybeNextUri = extractNextUri(response)
      acceptHeader = extractCurrentRequestAcceptHeader(request)
      body <- decodedResponseStream(response) ++ followNextLink(maybeNextUri, acceptHeader)
    } yield body
  }

  private def getUserTeams(): Stream[F, UserTeamResponse] =
    sendReceiveStream[UserTeamResponse](prepareRequest("/user/teams", Accept(json)))

  private def getTeamDiscussions(teamId: Long): Stream[F, TeamDiscussionResponse] =
    sendReceiveStream[TeamDiscussionResponse](prepareRequest(s"/teams/$teamId/discussions", previewAcceptHeader))

  private def getTeamDiscussionComments(teamId: Long, discussionId: Long): Stream[F, TeamDiscussionCommentsResponse] = {
    sendReceiveStream[TeamDiscussionCommentEntity](prepareRequest(s"/teams/$teamId/discussions/$discussionId/comments", previewAcceptHeader)).fold(List.empty[TeamDiscussionCommentEntity])((acc, comment) => comment :: acc)
  }

  def getTeamDiscussionsUpdatedAfter(instant: Instant): Stream[F, TeamDiscussionAggregateRoot] = {

    def filterDiscussions(teamDiscussionAggregateRoot: TeamDiscussionAggregateRoot): Boolean =
      if (teamDiscussionAggregateRoot.lastUpdated.isAfter(instant))
        true
      else
        false

    def latestDiscussionsForATeam(team: UserTeamResponse): Stream[F, TeamDiscussionAggregateRoot] = {
      val program = for {
        discussion <- getTeamDiscussions(team.id)
        comments <- getTeamDiscussionComments(team.id, discussion.number)
        domainDiscussion <- DiscussionRestAssembler.toDomain(team, discussion, comments).stream
      } yield domainDiscussion

      program.takeWhile(filterDiscussions)
    }

    val allDiscussionsForAllTeams = for {
      team <- getUserTeams()
      domainDiscussion <- latestDiscussionsForATeam(team)
      _ <- Stream.eval(Logger[F].info(s"New item found in ${ domainDiscussion.team.name } -> ${ domainDiscussion.discussion.title }"))
    } yield domainDiscussion

    allDiscussionsForAllTeams.handleErrorWith(t => Stream.eval(Logger[F].error(t)("Error getting discussions!")) >> Stream.empty)
  }
}

object GithubHttp4sApiClient {

  final case class UserTeamResponse(
      id: Long,
      name: String,
      description: String,
      created_at: Instant,
      updated_at: Instant)

  final case class AuthorEntity(id: Long, login: String, avatar_url: String)

  final case class TeamDiscussionResponse(
      title: String,
      number: Long,
      author: AuthorEntity,
      body: String,
      body_version: String,
      html_url: String,
      created_at: Instant,
      updated_at: Instant)

  type TeamDiscussionCommentsResponse = List[TeamDiscussionCommentEntity]

  final case class TeamDiscussionCommentEntity(
      author: AuthorEntity,
      number: Long,
      body: String,
      body_version: String,
      html_url: String,
      created_at: Instant,
      updated_at: Instant)

  def stream[F[_] : ConcurrentEffect : Timer : Logger](client: Client[F], gitConfig: GitConfig): Stream[F, GithubApiClient[F]] =
    new GithubHttp4sApiClient[F](client, gitConfig).delay[F].stream

  def stream[F[_] : ConcurrentEffect : Timer : Logger](gitConfig: GitConfig)(implicit ec: ExecutionContext, retryPolicy: RetryPolicy[F]): Stream[F, GithubApiClient[F]] =
    for {
      retryClient <- BlazeClientBuilder[F](ec).withRequestTimeout(gitConfig.requestTimeout).stream.map(Retry(retryPolicy)(_))
      githubClient <- stream(retryClient, gitConfig)
    } yield githubClient
}
