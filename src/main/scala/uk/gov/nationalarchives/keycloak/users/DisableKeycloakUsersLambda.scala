package uk.gov.nationalarchives.keycloak.users

import cats.effect._
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend, SttpBackendOptions}
import uk.gov.nationalarchives.keycloak.users.Config.{apiFromConfig, authFromConfig}
import uk.gov.nationalarchives.keycloak.users.DisableKeycloakUsersLambda.InactivityPayload
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.time.{ZoneId, ZonedDateTime}
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

class DisableKeycloakUsersLambda extends RequestHandler[ScheduledEvent, Boolean] {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(180.seconds))
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(Config.authUrl, "tdr", 60)

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)
  val keycloakUtils = new KeycloakUtils()
  val getConsignmentsClient = new GraphQLClient[gcs.Data, gcs.Variables](Config.apiUrl)
  val graphQlApi: GraphQlApiService = GraphQlApiService(keycloakUtils, getConsignmentsClient)

  @Override
  override def handleRequest(event: ScheduledEvent, context: Context): Boolean = {
    val detailJson = Json
      .fromFields(event.getDetail.asScala.toSeq.map { case (k, v) => k -> Json.fromString(v.toString) })
      .noSpaces

    val program: IO[Boolean] = for {
      payload <- IO.fromEither(decode[InactivityPayload](detailJson))
      _ <- IO(logger.info(s"[INFO] Processing user type: ${payload.userType}, inactivity period: ${payload.inactivityPeriod} months\n"))
      authConf <- authFromConfig()
      consignmentApiConf <- apiFromConfig()
      keycloak = KeycloakUsers.keyCloakAdminClient(authConf)
      users = DisableKeycloakUsers.findUsersCreatedBeforePeriod(keycloak, payload.userType, periodMonths = 6)
      _ <- IO(logger.info(s"Found ${users.length} ${payload.userType} users older than 6 months"))
      _ <- IO(logger.info(s"Found ${users.map(_.username)}"))
      consignmentsList <- IO.traverse(users) { user =>
        val consignments = graphQlApi.getConsignments(
          config = consignmentApiConf,
          userId = UUID.fromString(user.id)
        )
        DisableKeycloakUsers.fetchLatestConsignment(user, consignments)
      }
      testMe = List(Some(ConsignmentInfo("c98a665f-5ec2-4230-bcb6-555e7feb8ee7", "thanh-test-judgment", "TDR-2025-XNCQ", "judgment", ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()))))
      inactiveUsers <- DisableKeycloakUsers.disableInactiveUsers(keycloak, inactiveUsers = testMe.flatten, DisableKeycloakUsers.isOlderThanGivenPeriod, payload.inactivityPeriod)
      _ <- IO(logger.info(s"Disabled ${inactiveUsers.length} users: ${inactiveUsers.map(_._1)}"))
      _ = keycloak.close()
    } yield true

    program.unsafeRunSync()
  }
}

object DisableKeycloakUsersLambda {

  case class InactivityPayload(userType: String, inactivityPeriod: Int)
}
