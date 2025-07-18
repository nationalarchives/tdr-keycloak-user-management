package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import cats.implicits.toTraverseOps
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import io.circe._
import io.circe.generic.semiauto._
import org.keycloak.admin.client.Keycloak
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory

import java.time.{Instant, LocalDateTime, ZoneId, ZonedDateTime}
import scala.jdk.CollectionConverters._

object DisableKeycloakUsers {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val inactiveUserEncoder: Encoder[User] = deriveEncoder

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  private def formatDate(timestamp: Long): String = {
    val instant = Instant.ofEpochMilli(timestamp)
    LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toString
  }

  def findUsersCreatedBeforePeriod(keycloak: Keycloak, userType: String, periodMonths: Int): List[User] = {
    val realmResource = keycloak.realm("tdr")
    val usersResource = realmResource.users()

    val users = usersResource.list().asScala.toList

    val cutoffTime = LocalDateTime.now()
      .minusMonths(periodMonths)
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

  def isOlderThanGivenPeriodDays(consignment: UserAndConsignmentInfo, period: Int): Boolean = {
    consignment.latestDatetime.isBefore(ZonedDateTime.now().minusDays(period))
  }

  def disableInactiveUsers(
                            keycloak: Keycloak,
                            inactiveUsers: List[UserAndConsignmentInfo],
                            shouldDisable: (UserAndConsignmentInfo, Int) => Boolean,
                            inactivityPeriod: Int
                          ): IO[List[UserAndConsignmentInfo]] = {
    val realmResource = keycloak.realm("tdr")
    val usersResources = realmResource.users()

    inactiveUsers.filter(consignmentInfo => shouldDisable(consignmentInfo, inactivityPeriod)).traverse { user =>
      IO {
        val userResource = usersResources.get(user.userid)
        val userRep = userResource.toRepresentation()

        userRep.setEnabled(false)

        userResource.update(userRep)

        logger.info(s"Successfully disabled user ${user.username}")
        user.copy(isDisabled = true)
      }.handleError { e =>
        logger.error(s"Failed to disable user ${user.username}: ${e.getMessage}")
        user
      }
    }
  }

  def fetchLatestConsignment(user: User, consignments: IO[gcs.Consignments]): IO[Option[UserAndConsignmentInfo]] = {
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
          UserAndConsignmentInfo(
            user.id,
            user.username,
            node.consignmentReference,
            node.consignmentType.get,
            latestDate
          )
        }.sortBy(_.latestDatetime)(Ordering[ZonedDateTime].reverse).headOption
    }
  }

  case class User(
                   id: String,
                   username: String,
                   email: Option[String],
                   firstName: Option[String],
                   lastName: Option[String],
                   createdTimestamp: Option[String],
                   group: Option[List[String]]
                 )

  case class UserAndConsignmentInfo(
                                     userid: String,
                                     username: String,
                                     consignmentReference: String,
                                     consignmentType: String,
                                     latestDatetime: ZonedDateTime,
                                     isDisabled: Boolean = false
                                   )
}
