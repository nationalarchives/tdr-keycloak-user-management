package uk.gov.nationalarchives.keycloak.users

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.extension.{Parameters, ResponseDefinitionTransformer}
import com.github.tomakehurst.wiremock.http.{Request, RequestMethod, ResponseDefinition}
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.parser.decode
import uk.gov.nationalarchives.keycloak.users.LambdaSpecUtils.TestUserRequest

import java.nio.ByteBuffer
import java.nio.charset.Charset
import scala.jdk.CollectionConverters._

class LambdaSpecUtils(wiremockAuthServer: WireMockServer, wiremockSsmServer: WireMockServer) {
  implicit val customConfig: Configuration = Configuration.default.withDefaults

  val baseAdminUrl = "/auth/admin/realms/tdr/users"

  def setupSsmServer(): Unit = {
    wiremockSsmServer
      .stubFor(post(urlEqualTo("/"))
        .willReturn(okJson("{\"Parameter\":{\"Name\":\"string\",\"Value\":\"string\"}}"))
      )
  }

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
}

object LambdaSpecUtils {
  def apply(wireMockAuthServer: WireMockServer, wiremockSsmServer: WireMockServer) = new LambdaSpecUtils(wireMockAuthServer, wiremockSsmServer)

  case class Credentials(`type`: String, value: String)

  case class TestUserRequest(username: String,
                             firstName: String,
                             lastName: String,
                             email: String,
                             requiredActions: List[String] = Nil,
                             credentials: List[Credentials] = Nil,
                             groups: List[String])
}
