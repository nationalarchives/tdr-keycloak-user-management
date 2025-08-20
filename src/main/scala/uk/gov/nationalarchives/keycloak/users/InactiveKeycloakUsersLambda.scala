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
import uk.gov.nationalarchives.keycloak.users.Config.{authFromConfig, getClientSecret, reportingFromConfig}
import uk.gov.nationalarchives.keycloak.users.InactiveKeycloakUsersLambda.{EventInput, LambdaResponse}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

class InactiveKeycloakUsersLambda extends RequestHandler[ScheduledEvent, LambdaResponse] {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(180.seconds))
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(Config.authUrl, "tdr", 60)

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)
  val keycloakUtils = new KeycloakUtils()
  val getConsignmentsClient = new GraphQLClient[gcs.Data, gcs.Variables](Config.apiUrl)
  val graphQlApi: GraphQlApiService = GraphQlApiService(keycloakUtils, getConsignmentsClient)

  @Override
  override def handleRequest(event: ScheduledEvent, context: Context): LambdaResponse = {
    val detailJson = Json
      .fromFields(event.getDetail.asScala.toSeq.map { case (k, v) => k -> Json.fromString(v.toString) })
      .noSpaces

    val program = for {
      payload <- IO.fromEither(decode[EventInput](detailJson))
      _ <- IO(logger.info(s"[INFO] Processing user type: ${payload.userType}, inactivity period: ${payload.inactivityPeriodDays} days\n"))
      authConf <- authFromConfig()
      reportingConf <- reportingFromConfig()
      keycloak = KeycloakUsers.keyCloakAdminClient(authConf)
      eligibleUsers = InactiveKeycloakUsersUtils.findUsersCreatedBeforePeriod(keycloak, authConf, payload.userType, periodDays = payload.inactivityPeriodDays)
      _ <- IO(logger.info(s"Found ${eligibleUsers.length} ${payload.userType} users created over ${payload.inactivityPeriodDays} days ago"))
      clientSecret = getClientSecret(reportingConf.secretPath)
      eligibleUsersActivity <- IO.traverse(eligibleUsers) { user =>
        val consignments = graphQlApi.getConsignments(
          config = reportingConf,
          userId = UUID.fromString(user.id),
          clientSecret
        )
        InactiveKeycloakUsersUtils.fetchLatestUserActivity(user, consignments)
      }
      inactiveUsers <- InactiveKeycloakUsersUtils.disableInactiveUsers(keycloak, authConf, inactiveUsers = eligibleUsersActivity.flatten, InactiveKeycloakUsersUtils.userActivityOlderThanPeriod, payload.inactivityPeriodDays)
      _ = keycloak.close()
    } yield LambdaResponse(isSuccess = true, "Users disabled successfully: " + inactiveUsers.filter(_.isDisabled).map(_.userId).mkString(", "))

    program
      .handleErrorWith(error => {
        logger.error(s"Unexpected error:${error.getMessage}")
        IO.pure(LambdaResponse(isSuccess = false, error.getMessage))
      }).unsafeRunSync()
  }
}

object InactiveKeycloakUsersLambda {

  case class EventInput(userType: String, inactivityPeriodDays: Int)

  case class LambdaResponse(isSuccess: Boolean, message: String)
}
