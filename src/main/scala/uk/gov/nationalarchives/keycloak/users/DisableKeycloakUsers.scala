package uk.gov.nationalarchives.keycloak.users

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits.toTraverseOps
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import io.circe._
import io.circe.generic.semiauto._
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend, SttpBackendOptions}
import uk.gov.nationalarchives.keycloak.users.Config.{Auth, ConsignmentApi, apiFromConfig, authFromConfig}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.time.{Instant, LocalDateTime, ZoneId, ZonedDateTime}
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

case class User(
                 id: String,
                 username: String,
                 email: Option[String],
                 firstName: Option[String],
                 lastName: Option[String],
                 createdTimestamp: Option[String],
                 group: Option[List[String]]
               )

case class ConsignmentInfo(
                            userid: String,
                            username: String,
                            consignmentReference: String,
                            consignmentType: String,
                            latestDatetime: ZonedDateTime
                          )

object KeycloakInactiveUsers extends IOApp {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  implicit val inactiveUserEncoder: Encoder[User] = deriveEncoder

  private def initializeKeycloak(auth: Auth): Keycloak = {
    KeycloakBuilder.builder()
      .serverUrl(auth.url)
      .realm("tdr")
      .clientId(auth.client)
      .clientSecret(auth.secret)
      .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
      .build()
  }

  private def formatDate(timestamp: Long): String = {
    val instant = Instant.ofEpochMilli(timestamp)
    LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toString
  }

  private def findUsersCreatedBeforePeriod(keycloak: Keycloak, userType: String, periodMonths: Int): List[User] = {
    val realmResource = keycloak.realm("tdr")
    val usersResource = realmResource.users()

    val users = usersResource.list().asScala.toList

    val cutoffTime = LocalDateTime.now()
      .minusMonths(periodMonths) //use minusHours to test
      .atZone(ZoneId.systemDefault())
      .toInstant
      .toEpochMilli

    users.flatMap { user =>
      val userGroups = usersResource.get(user.getId).groups().asScala.map(_.getName)
      val userResource = usersResource.get(user.getId)
      val userRep = userResource.toRepresentation()

      if (!userRep.isEnabled || !userGroups.contains(userType)) {
        Nil
      } else {
        val createdTimeStamp = user.getCreatedTimestamp
        if (createdTimeStamp < cutoffTime) {
          Some(User(
            id = user.getId,
            username = user.getUsername,
            email = Option(user.getEmail),
            firstName = Option(user.getFirstName),
            lastName = Option(user.getLastName),
            createdTimestamp = Option(formatDate(user.getCreatedTimestamp)),
            group = Option(userGroups.toList)
          ))
        } else None
      }
    }
  }

  private def isOlderThanGivenPeriod(consignment: ConsignmentInfo, period: Int): Boolean = {
    consignment.latestDatetime.isBefore(ZonedDateTime.now().minusMonths(period))
  }

  private def disableInactiveUsers(
                                    keycloak: Keycloak,
                                    inactiveUsers: List[ConsignmentInfo],
                                    shouldDisable: (ConsignmentInfo, Int) => Boolean,
                                    inactivityPeriod: Int
                                  ): IO[List[(String, Boolean)]] = {
    val realmResource = keycloak.realm("tdr")
    val usersResources = realmResource.users()

    inactiveUsers.filter(consignmentInfo => shouldDisable(consignmentInfo, inactivityPeriod)).traverse { user =>
      IO {
        val userResource = usersResources.get(user.userid)
        val userRep = userResource.toRepresentation()

        userRep.setEnabled(false)

        val attributes: Map[String, List[String]] = Map(
          "disabledDate" -> List(LocalDateTime.now().toString),
          "disabledReason" -> List(s"Automatically disabled due to inactivity in the last ${inactivityPeriod} months")
        )

        val existingAttributes = Option(userRep.getAttributes)
          .map(_.asScala.toMap.view.mapValues(_.asScala.toList).toMap)
          .getOrElse(Map.empty)

        val mergedAttributes = existingAttributes ++ attributes

        userRep.setAttributes(mergedAttributes.view.mapValues(_.asJava).toMap.asJava)

        userResource.update(userRep)

        println(s"Successfully disabled: ${user.username}")
        (user.username, true)
      }.handleError { e =>
        println(s"Failed to disable user ${user.username}: ${e.getMessage}")
        (user.username, false)
      }
    }
  }

  private def fetchLatestConsignment(user: User, consignments: IO[gcs.Consignments]): IO[Option[ConsignmentInfo]] = {
    consignments.map { consignment =>
      consignment.edges
        .getOrElse(Nil) // List[Option[Edges]]
        .flatMap(_.toList) // List[Edges]
        .map(_.node)
        .map { node =>
          val created = node.createdDatetime
          val exported = node.exportDatetime
          val latestDate = (created, exported) match {
            case (Some(c), Some(e)) => if (c.isAfter(e)) c else e
            case (Some(c), None) => c
          }
          ConsignmentInfo(
            user.id,
            user.username,
            node.consignmentReference,
            node.consignmentType.get,
            latestDate
          )
        }.sortBy(_.latestDatetime)(Ordering[ZonedDateTime].reverse).headOption
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val userType = args.headOption.getOrElse(sys.exit())
    val inactivityPeriod = args.lift(1).map(_.toInt).getOrElse(6) // Default to 6 months if not provided

    val keycloakUtils = new KeycloakUtils()
    implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(180.seconds))
    implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(Config.authUrl, "tdr", 60)
    val getConsignmentsClient = new GraphQLClient[gcs.Data, gcs.Variables](Config.apiUrl)
    val graphQlApi: GraphQlApiService = GraphQlApiService(keycloakUtils, getConsignmentsClient)

    for {
      authConf <- authFromConfig()
      consignmentApiConf <- apiFromConfig()
      keycloak = initializeKeycloak(authConf)
      users = findUsersCreatedBeforePeriod(keycloak, userType, periodMonths = 6)
      _ = println(s"Found ${users.length} $userType users older than 6 months")
      _ = println(s"Found ${users.map(_.username)}")
      consignmentsList <- IO.traverse(users) { user =>
        val consignments = graphQlApi.getConsignments(
          config = consignmentApiConf,
          userId = UUID.fromString(user.id)
        )
        fetchLatestConsignment(user, consignments)
      }
      testMe = List(Some(ConsignmentInfo("c98a665f-5ec2-4230-bcb6-555e7feb8ee7", "thanh-test-judgment", "TDR-2025-XNCQ", "judgment", ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()))))
      _ <- disableInactiveUsers(keycloak, inactiveUsers = testMe.flatten, isOlderThanGivenPeriod, inactivityPeriod)
      _ = keycloak.close()
    } yield ExitCode.Success
  }
}