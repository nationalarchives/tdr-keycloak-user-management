package uk.gov.nationalarchives.keycloak.users

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.nationalarchives.keycloak.users.InactiveKeycloakUsersLambda.{LambdaResponse, LogInfo}

import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.jdk.CollectionConverters.MapHasAsJava

class InactiveKeycloakUsersLambdaSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach {
  val wiremockGraphqlServer = new WireMockServer(9001)
  val wiremockAuthServer = new WireMockServer(9002)
  val wiremockSsmServer = new WireMockServer(9003)
  val wiremockKmsServer = new WireMockServer(9004)
  val wiremockSnsServer = new WireMockServer(9005)
  val lambdaSpecUtils = new LambdaSpecUtils(wiremockAuthServer, wiremockSsmServer, Some(wiremockGraphqlServer), Some(wiremockSnsServer))
  
  override def beforeAll(): Unit = {
    super.beforeAll()
    lambdaSpecUtils.setupSsmServer()
    lambdaSpecUtils.wiremockKmsEndpoint.start()
    wiremockAuthServer.start()
    wiremockSsmServer.start()
    wiremockGraphqlServer.start()
    wiremockSnsServer.start()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    lambdaSpecUtils.wiremockKmsEndpoint.stop()
    wiremockAuthServer.stop()
    wiremockSsmServer.stop()
    wiremockGraphqlServer.stop()
    wiremockSnsServer.stop()
  }
  
  override def beforeEach(): Unit = wiremockSnsServer.resetRequests()
  
  "handleRequest" should "successfully disable inactive users" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriodDays" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.mockAuthServerUserResponse(userId)
    lambdaSpecUtils.mockGetConsignmentsResponse()
    lambdaSpecUtils.setupSnsServer()

    val response = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext())
    response shouldBe LambdaResponse(isSuccess = true, s"Users disabled successfully: $userId")
  }
  
  "handleRequest" should "publish appropriate event when a single user is disabled" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriodDays" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.mockAuthServerUserResponse(userId)
    lambdaSpecUtils.mockGetConsignmentsResponse()
    lambdaSpecUtils.setupSnsServer()

    val mockLogInfo = LogInfo("inactive-keycloak-users-log-group", "single-users-invocation-stream")
    new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext(mockLogInfo.logGroupName, mockLogInfo.logStreamName))
    
    val serveEvents = wiremockSnsServer.getAllServeEvents
    serveEvents.size() should equal(1)

    val snsRequest = serveEvents.get(0).getRequest
    val requestBody = snsRequest.getBodyAsString
    val decodedBody = URLDecoder.decode(requestBody, "UTF-8")

    decodedBody should include("\"environment\" : \"test\"")
    decodedBody should include("\"disabledUsersCount\" : 1")
    decodedBody should include(s"\"logGroupName\" : \"${mockLogInfo.logGroupName}\"")
    decodedBody should include(s"\"logStreamName\" : \"${mockLogInfo.logStreamName}\"")
  }

  "handleRequest" should "publish appropriate event when multiple users are disabled" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriodDays" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    val userId1 = UUID.randomUUID().toString
    val userId2 = UUID.randomUUID().toString
    val userId3 = UUID.randomUUID().toString
    
    lambdaSpecUtils.mockAuthServerUserResponse(Seq(userId1, userId2, userId3))
    lambdaSpecUtils.mockGetConsignmentsResponse()
    
    lambdaSpecUtils.setupSnsServer()

    val mockLogInfo = LogInfo("inactive-keycloak-users-log-group", "multi-users-invocation-stream")

    new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext(mockLogInfo.logGroupName, mockLogInfo.logStreamName))

    val serveEvents = wiremockSnsServer.getAllServeEvents
    serveEvents.size() should equal(1)

    val snsRequest = serveEvents.get(0).getRequest
    val requestBody = snsRequest.getBodyAsString
    val decodedBody = URLDecoder.decode(requestBody, "UTF-8")

    decodedBody should include("\"environment\" : \"test\"")
    decodedBody should include("\"disabledUsersCount\" : 3")
    decodedBody should include(s"\"logGroupName\" : \"${mockLogInfo.logGroupName}\"")
    decodedBody should include(s"\"logStreamName\" : \"${mockLogInfo.logStreamName}\"")
  }

  "handleRequest" should "fail if the scheduledEvent parameters contains invalid json" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "badField" -> "badValue"
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
    scheduleEvent.setDetail(eventDetailsJava)

    val result = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext())

    result shouldBe LambdaResponse(isSuccess = false, "DecodingFailure at .userType: Missing required field")
  }

  "handleRequest" should "fail if call to the keycloak api fails" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriodDays" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    lambdaSpecUtils.mockAuthServerUserResponse()
    wiremockAuthServer.stubFor(
      get(urlEqualTo("/auth/admin/realms/tdr/users"))
        .willReturn(
          aResponse()
            .withStatus(500)
        )
    )

    val result = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext())

    result shouldBe LambdaResponse(isSuccess = false, "HTTP 500 Internal Server Error")
  }

  "handleRequest" should "throw an exception if call to the graphql fails" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriod" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    lambdaSpecUtils.mockAuthServerUserResponse()
    wiremockGraphqlServer.stubFor(
      post(urlEqualTo("/graphql"))
        .withRequestBody(containing("getConsignments"))
        .willReturn(aResponse()
          .withStatus(500)
          .withBody("Internal Server Error"))
    )

    LambdaResponse(isSuccess = false, "HTTP 500 Internal Server Error")
  }

  "handleRequest" should "not disable a user if their last activity is within 180 days" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriodDays" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    lambdaSpecUtils.mockAuthServerUserResponse()

    val currentDateTime = ZonedDateTime.now(java.time.ZoneOffset.UTC)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val formattedDateTime = currentDateTime.format(formatter)

    lambdaSpecUtils.mockGetConsignmentsResponse(Some(formattedDateTime))

    val response = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext())
    response shouldBe LambdaResponse(isSuccess = true, "Users disabled successfully: ")
  }

  "handleRequest" should "publish appropriate event when no users are disabled" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "userType" -> "judgment_user",
      "inactivityPeriodDays" -> 180
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]

    scheduleEvent.setDetail(eventDetailsJava)

    lambdaSpecUtils.mockAuthServerUserResponse()

    val currentDateTime = ZonedDateTime.now(java.time.ZoneOffset.UTC)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val formattedDateTime = currentDateTime.format(formatter)

    lambdaSpecUtils.mockGetConsignmentsResponse(Some(formattedDateTime))

    lambdaSpecUtils.setupSnsServer()
    
    val mockLogInfo = LogInfo("inactive-keycloak-users-log-group", "no-users-invocation-stream")
    new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, lambdaSpecUtils.mockContext(mockLogInfo.logGroupName, mockLogInfo.logStreamName))

    val serveEvents = wiremockSnsServer.getAllServeEvents
    serveEvents.size() should equal(1)

    val snsRequest = serveEvents.get(0).getRequest
    val requestBody = snsRequest.getBodyAsString
    val decodedBody = URLDecoder.decode(requestBody, "UTF-8")

    decodedBody should include("\"environment\" : \"test\"")
    decodedBody should include("\"disabledUsersCount\" : 0")
    decodedBody should include(s"\"logGroupName\" : \"${mockLogInfo.logGroupName}\"")
    decodedBody should include(s"\"logStreamName\" : \"${mockLogInfo.logStreamName}\"")
  }
}
