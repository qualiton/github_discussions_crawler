package org.qualiton.crawler.testsupport.wiremock

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.{ HttpStatus => HttpStatusCheck }
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import io.circe.parser._
import io.circe.Json
import org.apache.http.HttpStatus

import org.qualiton.crawler.testsupport.resource.ResourceSupport
import org.qualiton.crawler.testsupport.wiremock.GithubApiV3MockServer._

class GithubApiV3MockServer(githubApiV3Port: Int = GithubApiV3Port) extends ResourceSupport {

  val server = new WireMockServer(wireMockConfig().port(githubApiV3Port))

  def startMockServer(): Unit = {
    server.start()
    configureFor(GithubApiV3Host, githubApiV3Port)
  }

  def stopMockServer(): Unit = {
    server.stop()
  }

  private def errorResponse: String =
    s"""
       |{
       |    "message": "Not Found",
       |    "documentation_url": "https://developer.github.com/v3/users/#get-a-single-user"
       |}
    """.stripMargin

  private def prepareResponse(resource: String, httpStatus: Int): String = {
    if (HttpStatusCheck.isSuccess(httpStatus)) {
      loadResource(resource)
    } else {
      errorResponse
    }
  }

  private val fixedInstant = Instant.parse("2018-11-10T08:09:45Z")

  def mockUserTeams(teamId: Long, httpStatus: Int, resource: String = "/userteams.json"): StubMapping =
    server.stubFor(get(urlPathMatching("/user/teams"))
      .withHeader("Authorization", equalTo(s"Basic $testEncodedApiToken"))
      .willReturn(aResponse()
        .withStatus(httpStatus)
        .withHeader("Content-type", "application/json; charset=UTF-8")
        .withBody(prepareResponse(resource, httpStatus)
          .replaceAll("TEAM_ID", teamId.toString))))

  def mockTeamDiscussions(teamId: Long, numberOfDiscussion: Int, numberOfComments: Int, httpStatus: Int, resource: String = "/teamDiscussionsItem.json"): StubMapping = {
    val body: String = {
      val counter = new AtomicLong(1)

      def discussionArrayItem = {
        val current = counter.getAndIncrement()
        mockDiscussionComments(teamId, current, numberOfComments, httpStatus)
        parse(loadResource(resource)
          .replaceAll("TEAM_ID", teamId.toString)
          .replaceAll("BODY", s"discussion-body-$current")
          .replaceAll("COMMENTS", numberOfComments.toString)
          .replaceAll("DISCUSSION_ID", current.toString)
          .replaceAll("CREATED_AT", fixedInstant.plus(current, ChronoUnit.HOURS).toString)
          .replaceAll("NUMBER", current.toString)
          .replaceAll("TITLE", s"discussion-title-$current"))
      }

      Json.fromValues(
        Stream
          .continually(discussionArrayItem)
          .take(numberOfDiscussion)
          .toList
          .reverse
          .sequence
          .getOrElse(List.empty))
        .toString()
    }

    server.stubFor(get(urlPathMatching(s"/teams/$teamId/discussions"))
      .withHeader("Authorization", equalTo(s"Basic $testEncodedApiToken"))
      .willReturn(aResponse()
        .withStatus(httpStatus)
        .withHeader("Content-type", "application/json; charset=UTF-8")
        .withBody(body)))
  }

  def mockDiscussionComments(teamId: Long, discussionId: Long, numberOfComments: Int, httpStatus: Int, resource: String = "/discussionCommentsItem.json"): StubMapping = {
    val body: String = {
      val counter = new AtomicLong(1)

      def commentArrayItem = {
        val current = counter.getAndIncrement()
        parse(loadResource(resource)
          .replaceAll("TEAM_ID", teamId.toString)
          .replaceAll("BODY", s"comment-body-$current")
          .replaceAll("DISCUSSION_ID", discussionId.toString)
          .replaceAll("COMMENT_ID", current.toString)
          .replaceAll("CREATED_AT", fixedInstant.plus(current, ChronoUnit.HOURS).toString)
          .replaceAll("NUMBER", current.toString))
      }

      Json.fromValues(
        Stream
          .continually(commentArrayItem)
          .take(numberOfComments)
          .toList
          .reverse
          .sequence
          .getOrElse(List.empty))
        .toString()
    }

    server.stubFor(get(urlPathMatching(s"/teams/$teamId/discussions/$discussionId/comments"))
      .withHeader("Authorization", equalTo(s"Basic $testEncodedApiToken"))
      .willReturn(aResponse()
        .withStatus(httpStatus)
        .withHeader("Content-type", "application/json; charset=UTF-8")
        .withBody(body)))
  }

  def mockDiscussions(teamId: Long, numberOfDiscussion: Int, numberOfComments: Int, httpStatus: Int): StubMapping = {
    mockUserTeams(teamId, HttpStatus.SC_OK)
    mockTeamDiscussions(teamId, numberOfDiscussion, numberOfComments, httpStatus)
  }
}

object GithubApiV3MockServer {
  val GithubApiV3Host: String = "localhost"
  val GithubApiV3Port: Int = 3000

  val testApiToken: String = "testApiToken"
  val testEncodedApiToken: String = "dGVzdEFwaVRva2Vu"
}
