package uk.gov.nationalarchives.keycloak.users

import cats.implicits._
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext.Http
import com.github.tomakehurst.wiremock.WireMockServer
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.nationalarchives.keycloak.users.KeycloakUsers.UserCredentials
import uk.gov.nationalarchives.keycloak.users.LambdaSpecUtils.TestUserRequest

import java.util.UUID

class ApiLambdaSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  val wiremockAuthServer = new WireMockServer(9002)
  val lambdaSpecUtils = new LambdaSpecUtils(wiremockAuthServer)
  
  override def beforeAll(): Unit = {
    super.beforeAll()
    lambdaSpecUtils.wiremockKmsEndpoint.start()
    wiremockAuthServer.start()
  }
  override def afterAll(): Unit = {
    super.afterAll()
    lambdaSpecUtils.wiremockKmsEndpoint.stop()
    wiremockAuthServer.stop()
  }

  def userCredentials: (Option[String], Option[Boolean]) => String = UserCredentials("username", "test@test.com", _,
    "First Name", "Last Name", "Body".some, "UserType".some, _).asJson.printWith(Printer.noSpaces)

  def allCredentials: String = userCredentials("Password12".some, true.some)
  def passwordSet: String = userCredentials("Password12".some, false.some)
  def passwordUnset: String = userCredentials(None, false.some)
  def sendEmailMissing: String = userCredentials(None, None)
  def sendEmailTrue: String = userCredentials(None, true.some)
  def sendEmailFalse: String = userCredentials(None, false.some)

  def event(body: String): APIGatewayV2HTTPEvent = {
    val event = new APIGatewayV2HTTPEvent()
    val requestContext = new RequestContext()
    val http = new Http()
    http.setMethod("POST")
    requestContext.setHttp(http)
    event.setRequestContext(requestContext)
    event.setBody(body)
    event
  }
  "handleRequest" should "return the created user ID from Keycloak" in {
    val apiGatewayEvent = event(passwordSet)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    val response = new ApiLambda().handleRequest(apiGatewayEvent, null)
    response should equal(userId)
  }

  "handleRequest" should "create a user with a password if the password is set" in {
    val apiGatewayEvent = event(passwordSet)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new ApiLambda().handleRequest(apiGatewayEvent, null)

    val body = lambdaSpecUtils.userCreateCalls.head.getRequest.getBodyAsString
    val request: TestUserRequest = lambdaSpecUtils.testRequest(body)
    request.credentials.size should equal(1)
    request.credentials.head.`type` should equal("password")
    request.credentials.head.value should equal("Password12")
  }

  "handleRequest" should "not create credentials if the password is not set" in {
    val apiGatewayEvent = event(passwordUnset)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new ApiLambda().handleRequest(apiGatewayEvent, null)

    val body = lambdaSpecUtils.userCreateCalls.head.getRequest.getBodyAsString
    val request: TestUserRequest = lambdaSpecUtils.testRequest(body)
    request.credentials.size should equal(0)
  }

  "handleRequest" should "not send an email if sendEmail is missing" in {
    val apiGatewayEvent = event(sendEmailMissing)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new ApiLambda().handleRequest(apiGatewayEvent, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(0)
  }

  "handleRequest" should "not send an email if sendEmail is false" in {
    val apiGatewayEvent = event(sendEmailFalse)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new ApiLambda().handleRequest(apiGatewayEvent, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(0)
  }

  "handleRequest" should "send an email if sendEmail is true" in {
    val apiGatewayEvent = event(sendEmailTrue)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new ApiLambda().handleRequest(apiGatewayEvent, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(1)
  }

  "handleRequest" should "send the correct fields" in {
    val apiGatewayEvent = event(allCredentials)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new ApiLambda().handleRequest(apiGatewayEvent, null)

    val body = lambdaSpecUtils.userCreateCalls.head.getRequest.getBodyAsString
    val request: TestUserRequest = lambdaSpecUtils.testRequest(body)
    request.username should equal("username")
    request.firstName should equal("First Name")
    request.lastName should equal("Last Name")
    request.groups.sorted.min should equal("/transferring_body_user/Body")
    request.groups.sorted.max should equal("/user_type/UserType_user")
  }
}
