package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import cats.implicits.toTraverseOps
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import io.circe._
import io.circe.generic.semiauto._
import org.keycloak.admin.client.Keycloak
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import uk.gov.nationalarchives.keycloak.users.Config.Auth

import java.time.{LocalDateTime, ZoneId, ZonedDateTime}
import scala.jdk.CollectionConverters._

object InactiveKeycloakUsersUtils {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val inactiveUserEncoder: Encoder[User] = deriveEncoder

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  def findUsersCreatedBeforePeriod(keycloak: Keycloak, authConf: Auth, userType: String, periodDays: Int): List[User] = {
    val realmResource = keycloak.realm(authConf.realm)
    val usersResource = realmResource.users()

    val users = usersResource.list().asScala.toList

    val cutoffTime = LocalDateTime.now()
      .minusDays(periodDays)
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
            id = user.getId
          ))
        } else None
      }
    }
  }

  def userActivityOlderThanPeriod(userActivity: UserActivity, periodDays: Int): Boolean = {
    userActivity.lastestActivityDatetime.isBefore(ZonedDateTime.now().minusDays(periodDays))
  }

  def disableInactiveUsers(
                            keycloak: Keycloak,
                            authConf: Auth,
                            inactiveUsers: List[UserActivity],
                            shouldDisable: (UserActivity, Int) => Boolean,
                            inactivityPeriod: Int,
                            dryRun: Boolean
                          ): IO[List[UserActivity]] = {
    val realmResource = keycloak.realm(authConf.realm)
    val usersResources = realmResource.users()

    inactiveUsers.filter(consignmentInfo => shouldDisable(consignmentInfo, inactivityPeriod)).traverse { user =>
      IO {
        val userResource = usersResources.get(user.userId)
        val userRep = userResource.toRepresentation()

        if (dryRun) {} else {
          userRep.setEnabled(false)
          userResource.update(userRep)
        }

        logger.info(s"Successfully disabled user ${user.userId}")
        user.copy(isDisabled = true)
      }.handleError { e =>
        logger.error(s"Failed to disable user ${user.userId}: ${e.getMessage}")
        user
      }
    }
  }

  def fetchLatestUserActivity(user: User, consignments: IO[gcs.Consignments]): IO[Option[UserActivity]] = {
    consignments.map { consignment =>
      consignment.edges
        .getOrElse(Nil)
        .flatMap(_.toList)
        .map(_.node)
        .map { node =>
          val created = node.createdDatetime
          val exported = node.exportDatetime
          val latestDate = (created, exported) match {
            case (Some(c), Some(e)) => if (c.isAfter(e)) c else e
            case (Some(c), None) => c
          }
          UserActivity(
            user.id,
            latestDate
          )
        }.sortBy(_.lastestActivityDatetime)(Ordering[ZonedDateTime].reverse).headOption
    }
  }

  case class User(id: String)

  case class UserActivity(
                           userId: String,
                           lastestActivityDatetime: ZonedDateTime,
                           isDisabled: Boolean = false
                         )
}
