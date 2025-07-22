package uk.gov.nationalarchives.keycloak.users

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.nationalarchives.keycloak.users.InactiveKeycloakUsersLambda.LambdaResponse

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.jdk.CollectionConverters.MapHasAsJava

class InactiveKeycloakUsersLambdaSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterAll {
  val wiremockGraphqlServer = new WireMockServer(9001)
  val wiremockAuthServer = new WireMockServer(9002)
  val wiremockSsmServer = new WireMockServer(9003)
  val wiremockKmsServer = new WireMockServer(9004)
  val lambdaSpecUtils = new LambdaSpecUtils(wiremockAuthServer, wiremockSsmServer)

  override def beforeAll(): Unit = {
    super.beforeAll()
    lambdaSpecUtils.setupSsmServer()
    lambdaSpecUtils.wiremockKmsEndpoint.start()
    wiremockAuthServer.start()
    wiremockSsmServer.start()
    wiremockGraphqlServer.start()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    lambdaSpecUtils.wiremockKmsEndpoint.stop()
    wiremockAuthServer.stop()
    wiremockSsmServer.stop()
    wiremockGraphqlServer.stop()
  }

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

    wiremockGraphqlServer.stubFor(
      post(urlEqualTo("/graphql"))
        .withRequestBody(containing("getConsignments"))
        .willReturn(okJson(
          """
      {
        "data": {
          "consignments": {
            "edges": [
              {
                "node": {
                  "consignmentid": "c98a665f-5ec2-4230-bcb6-555e7feb8ee7",
                  "consignmentReference": "TDR-2025-XNCQ",
                  "consignmentType": "judgment",
                  "exportDatetime": "2024-01-01T00:00:00Z",
                  "createdDatetime": "2024-01-01T00:00:00Z",
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


    val response = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, null)
    response shouldBe LambdaResponse(isSuccess = true, s"Users disabled successfully: $userId")
  }

  "handleRequest" should "fail if the scheduledEvent parameters contains invalid json" in {
    val scheduleEvent = new ScheduledEvent()
    val eventDetailsScala: Map[String, Any] = Map(
      "badField" -> "badValue"
    )
    val eventDetailsJava: java.util.Map[String, AnyRef] = eventDetailsScala.asJava.asInstanceOf[java.util.Map[String, AnyRef]]
    scheduleEvent.setDetail(eventDetailsJava)

    val result = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, null)

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

    val result = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, null)

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

  "handleRequest" should "not disable a user if their last activity is within 6 months" in {
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

    wiremockGraphqlServer.stubFor(
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
                  "exportDatetime": "$formattedDateTime",
                  "createdDatetime": "$formattedDateTime",
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

    val response = new InactiveKeycloakUsersLambda().handleRequest(scheduleEvent, null)
    response shouldBe LambdaResponse(isSuccess = true, "Users disabled successfully: ")
  }
}
