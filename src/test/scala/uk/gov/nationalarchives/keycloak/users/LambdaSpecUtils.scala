package uk.gov.nationalarchives.keycloak.users

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.extension.{Parameters, ResponseDefinitionTransformer}
import com.github.tomakehurst.wiremock.http.{Request, RequestMethod, ResponseDefinition}
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.parser.decode
import org.mockito.MockitoSugar.mock
import org.mockito.Mockito.when
import uk.gov.nationalarchives.keycloak.users.LambdaSpecUtils.TestUserRequest

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.UUID
import scala.jdk.CollectionConverters._

class LambdaSpecUtils(wiremockAuthServer: WireMockServer, wiremockSsmServer: WireMockServer, wiremockGraphqlServer: Option[WireMockServer] = None, wiremockSnsServer: Option[WireMockServer] = None) {
  implicit val customConfig: Configuration = Configuration.default.withDefaults

  val baseAdminUrl = "/auth/admin/realms/tdr/users"

  def setupSsmServer(): Unit = {
    wiremockSsmServer
      .stubFor(post(urlEqualTo("/"))
        .willReturn(okJson("{\"Parameter\":{\"Name\":\"string\",\"Value\":\"string\"}}"))
      )
  }

  def setupSnsServer(): Unit =
    wiremockSnsServer.foreach(_.stubFor(post(urlEqualTo("/")).willReturn(aResponse().withStatus(200))))
  
  val wiremockKmsEndpoint = new WireMockServer(new WireMockConfiguration().port(9004).extensions(new ResponseDefinitionTransformer {
    override def transform(request: Request, responseDefinition: ResponseDefinition, files: FileSource, parameters: Parameters): ResponseDefinition = {
      case class KMSRequest(CiphertextBlob: String)
      decode[KMSRequest](request.getBodyAsString) match {
        case Left(err) => throw err
        case Right(req) =>
          val charset = Charset.defaultCharset()
          val plainText = charset.newDecoder.decode(ByteBuffer.wrap(req.CiphertextBlob.getBytes(charset))).toString
          ResponseDefinitionBuilder
            .like(responseDefinition)
            .withBody(s"""{"Plaintext": "$plainText"}""")
            .build()
      }
    }

    override def getName: String = ""
  }))
  wiremockKmsEndpoint.stubFor(post(urlEqualTo("/")))

  def setupAuthServer(userId: String): Unit = {

    wiremockAuthServer.stubFor(post(urlEqualTo("/auth/realms/tdr/protocol/openid-connect/token"))
      .willReturn(okJson("{\"access_token\": \"abcde\"}"))
    )
    wiremockAuthServer.stubFor(post(urlEqualTo(s"$baseAdminUrl")).willReturn(aResponse().withStatus(201).withHeader("Location", s"/$userId")))
    wiremockAuthServer.stubFor(put(urlEqualTo(s"$baseAdminUrl/$userId/execute-actions-email")).willReturn(status(200)))
  }

  def mockAuthServerUserResponse(userIds: Seq[String] = Seq(UUID.randomUUID().toString)): Unit = {
    wiremockAuthServer.stubFor(post(urlEqualTo("/auth/realms/tdr/protocol/openid-connect/token"))
      .willReturn(okJson("{\"access_token\": \"abcde\"}"))
    )
    
    val usersJsonArray = userIds.zipWithIndex.map { case (userId, index) =>
      s"""
    {
      "id": "$userId",
      "username": "testuser$index",
      "email": "testuser$index@example.com",
      "enabled": true
    }"""
    }.mkString(",")

    wiremockAuthServer.stubFor(
      get(urlEqualTo(s"/auth/admin/realms/tdr/users?first=1&max=${Integer.MAX_VALUE}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(s"[$usersJsonArray]")
        )
    )

    userIds.zipWithIndex.foreach { case (userId, index) =>
      wiremockAuthServer.stubFor(
        get(urlEqualTo(s"/auth/admin/realms/tdr/users/$userId/groups"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withHeader("Content-Type", "application/json")
              .withBody(
                """
            [
              {
                "id": "group-id-1",
                "name": "judgment_user",
                "path": "/judgment_users"
              },
              {
                "id": "group-id-2",
                "name": "some-other-group",
                "path": "/some-other-group"
              }
            ]
            """.stripMargin
              )
          )
      )

      wiremockAuthServer.stubFor(
        get(urlEqualTo(s"/auth/admin/realms/tdr/users/$userId"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withHeader("Content-Type", "application/json")
              .withBody(
                s"""
          {
            "id": "$userId",
            "username": "testuser$index",
            "email": "testuser$index@example.com",
            "enabled": true,
            "firstName": "Test$index",
            "lastName": "User$index"
          }
          """.stripMargin
              )
          )
      )

      wiremockAuthServer.stubFor(
        put(urlEqualTo(s"/auth/admin/realms/tdr/users/$userId"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withHeader("Content-Type", "application/json")
              .withBody(
                s"""
          {
            "id": "$userId",
            "username": "testuser$index",
            "email": "testuser$index@example.com",
            "enabled": false,
            "firstName": "Test$index",
            "lastName": "User$index"
          }
          """.stripMargin
              )
          )
      )
    }
  }

  def mockAuthServerUserResponse(userId: String): Unit = {
    mockAuthServerUserResponse(Seq(userId))
  }

  def mockGetConsignmentsResponse(dateTime: Option[String] = None): StubMapping = {
    wiremockGraphqlServer.get.stubFor(
      post(urlEqualTo("/graphql"))
        .withRequestBody(containing("getConsignments"))
        .willReturn(okJson(
          s"""
      {
        "data": {
          "consignments": {
            "edges": [
              {
                "node": {
                  "consignmentid": "c98a665f-5ec2-4230-bcb6-555e7feb8ee7",
                  "consignmentReference": "TDR-2025-XNCQ",
                  "consignmentType": "judgment",
                  "exportDatetime": "${dateTime.getOrElse("2024-01-01T00:00:00Z")}",
                  "createdDatetime": "${dateTime.getOrElse("2024-01-01T00:00:00Z")}",
                  "consignmentStatuses": [],
                  "totalFiles": 100
                },
                "cursor": "abc"
              }
            ],
            "pageInfo": {
              "hasNextPage": false,
              "endCursor": null
            },
            "totalPages": 1
          }
        }
      }
      """
        ))
    )
  }

  def userCreateCalls: List[ServeEvent] = {
    wiremockAuthServer.getAllServeEvents.asScala
      .filter(s => s.getRequest.getUrl == baseAdminUrl && s.getRequest.getMethod == RequestMethod.POST)
      .toList
  }

  def sendEmailCalls(userId: String): List[ServeEvent] = {
    wiremockAuthServer.getAllServeEvents.asScala
      .filter(s => s.getRequest.getUrl == s"$baseAdminUrl/$userId/execute-actions-email" && s.getRequest.getMethod == RequestMethod.PUT)
      .toList
  }

  def testRequest(body: String): TestUserRequest = {
    decode[TestUserRequest](body) match {
      case Left(err) => throw new Exception(err)
      case Right(request) => request
    }
  }

  def mockContext(logGroupName: String = "test-log-group", logStreamName: String = "test-log-stream"): Context = {
    val mockContext = mock[Context]
    when(mockContext.getLogGroupName).thenReturn(logGroupName)
    when(mockContext.getLogStreamName).thenReturn(logStreamName)
    mockContext
  }
}

object LambdaSpecUtils {
  def apply(wireMockAuthServer: WireMockServer, wiremockSsmServer: WireMockServer, wiremockGraphqlServer: Option[WireMockServer] = None) = new LambdaSpecUtils(wireMockAuthServer, wiremockSsmServer, wiremockGraphqlServer)

  case class Credentials(`type`: String, value: String)

  case class TestUserRequest(username: String,
                             firstName: String,
                             lastName: String,
                             email: String,
                             requiredActions: List[String] = Nil,
                             credentials: List[Credentials] = Nil,
                             groups: List[String])
}
