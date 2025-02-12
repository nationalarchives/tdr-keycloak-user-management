package uk.gov.nationalarchives.keycloak.users

import cats.effect.unsafe.implicits.global
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import uk.gov.nationalarchives.keycloak.users.Config.{Auth, authFromConfig}

import java.time.{Instant, LocalDateTime, ZoneId}
import scala.jdk.CollectionConverters._

case class InactiveUser(
                         id: String,
                         username: String,
                         email: Option[String],
                         firstName: Option[String],
                         lastName: Option[String],
                         lastLoginDate: Option[String]  // Added for human-readable date
                       )

object KeycloakInactiveUsers extends App {

  implicit val inactiveUserEncoder: Encoder[InactiveUser] = deriveEncoder

  private def initializeKeycloak(auth: Auth): Keycloak =  {
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

  private def findInactiveUsers(keycloak: Keycloak, inactivityPeriodDays: Int): List[InactiveUser] = {
    val realmResource = keycloak.realm("tdr")
    val usersResource = realmResource.users()

    val users = usersResource.list().asScala.toList

    val cutoffTime = LocalDateTime.now()
      .minusDays(inactivityPeriodDays) //use minusHours to test
      .atZone(ZoneId.systemDefault())
      .toInstant
      .toEpochMilli

    users.flatMap { user =>
      val userResource = usersResource.get(user.getId)
      val userRep = userResource.toRepresentation()

      if (!userRep.isEnabled) {
        Nil
      } else {
        val loginEvents = realmResource
          .getEvents(
            java.util.Arrays.asList("LOGIN"),
            null, //client
            user.getId,
            null, // dateFrom
            null, // dateTo
            null, // ipAddress
            0, // firstResult
            1 // maxResults - we only need the most recent
          ).asScala.toList

        val lastLogin = loginEvents.headOption.map(_.getTime)
        if (lastLogin.isEmpty || lastLogin.exists(_ < cutoffTime)) {
          Some(InactiveUser(
            id = user.getId,
            username = user.getUsername,
            email = Option(user.getEmail),
            firstName = Option(user.getFirstName),
            lastName = Option(user.getLastName),
            lastLoginDate = lastLogin.map(formatDate)
          ))
        } else None
      }
    }
  }

  private def disableInactiveUsers(keycloak: Keycloak, inactiveUsers: List[InactiveUser]) : List[(String, Boolean)] = {
    val realmResource = keycloak.realm("tdr")
    val usersResources = realmResource.users()

    inactiveUsers.map { user =>
      try {
        val userResource = usersResources.get(user.id)
        val userRep = userResource.toRepresentation()

        userRep.setEnabled(false)

        // Create Scala Map for attributes
        val attributes: Map[String, List[String]] = Map(
          "disabledDate" -> List(LocalDateTime.now().toString),
          "disabledReason" -> List("Automatically disabled due to inactivity")
        )

        // Convert existing attributes to Scala Map and merge with new attributes
        val existingAttributes = Option(userRep.getAttributes)
          .map(_.asScala.toMap.view.mapValues(_.asScala.toList).toMap)
          .getOrElse(Map.empty)

        // Merge existing and new attributes
        val mergedAttributes = existingAttributes ++ attributes

        // Convert back to Java Map and Lists
        userRep.setAttributes(mergedAttributes.view.mapValues(_.asJava).toMap.asJava)

        userResource.update(userRep)

        (user.username, true)
      } catch {
        case e: Exception =>
          println(s"Failed to disable user ${user.username}: ${e.getMessage}")
          (user.username, false)
      }
    }
  }

  private def removeExistingOTPConfiguration(keycloak: Keycloak, userId: String): Boolean = {
    try {
      val realmResource = keycloak.realm("tdr")
      val userResource = realmResource.users().get(userId)

      // Remove OTP credentials
      val credentials = userResource.credentials().asScala
      credentials
        .filter(_.getType == "otp")
        .foreach(cred => userResource.removeCredential(cred.getId))

      true
    } catch {
      case e: Exception =>
        println(s"Failed to remove existing OTP configuration for user $userId: ${e.getMessage}")
        false
    }
  }

  //Alternative enforeOTP instead of disabling user
  private def enforceOTPForUsers(keycloak: Keycloak, inactiveUsers: List[InactiveUser]): List[(String, Boolean)] = {
    val realmResource = keycloak.realm("tdr")
    val usersResources = realmResource.users()

    inactiveUsers.map { user =>
      try {
        val userResource = usersResources.get(user.id)
        val userRep = userResource.toRepresentation()

        // First remove any existing OTP configuration
        val otpRemoved = removeExistingOTPConfiguration(keycloak, user.id)
        if (!otpRemoved) {
          throw new Exception("Failed to remove existing OTP configuration")
        }

        // Set required actions for new OTP setup
        val requiredActions = new java.util.ArrayList[String]()
        requiredActions.add("CONFIGURE_TOTP") // Require OTP setup
        userRep.setRequiredActions(requiredActions)

        // Add attributes to track when OTP was enforced
        val attributes: Map[String, List[String]] = Map(
          "otpEnforcedDate" -> List(LocalDateTime.now().toString),
          "otpEnforcedReason" -> List("Automatically enforced due to inactivity - Previous OTP configuration removed")
        )

        val existingAttributes = Option(userRep.getAttributes)
          .map(_.asScala.toMap.view.mapValues(_.asScala.toList).toMap)
          .getOrElse(Map.empty)

        val mergedAttributes = existingAttributes ++ attributes
        userRep.setAttributes(mergedAttributes.view.mapValues(_.asJava).toMap.asJava)

        userResource.update(userRep)

        (user.username, true)
      } catch {
        case e: Exception =>
          println(s"Failed to enforce OTP for user ${user.username}: ${e.getMessage}")
          (user.username, false)
      }
    }
  }

  private def exportInactiveUsers(users: List[InactiveUser], filename: String): Unit = {
    val json = users.asJson.spaces2
    scala.reflect.io.File(filename).writeAll(json)

    // Also print a summary to console
    println("\nInactive Users Summary:")
    println("------------------------")
    users.foreach { user =>
      println(s"""
                 |Username: ${user.username}
                 |Last Login: ${user.lastLoginDate.getOrElse("Never")}
                 |Email: ${user.email.getOrElse("N/A")}
                 |""".stripMargin)
    }
  }

  val keycloak = initializeKeycloak(authFromConfig().unsafeRunSync())
  try {
    val inactiveUsers = findInactiveUsers(keycloak, inactivityPeriodDays = 30)

    println(s"Found ${inactiveUsers.length} inactive users")

    //DISABLE THE USERS
/*    val results = disableInactiveUsers(keycloak, inactiveUsers)

    println("\nDisable Results:")
    println("----------------")
    results.foreach {
      case (username, true) => println(s"Successfully disabled: $username")
      case (username, false) => println(s"Failed to disable: $username")
    }

    val successCount = results.count(_._2)
    println(s"\nSummary: Successfully disabled $successCount out of ${results.length} users")*/

    //ENFORCE OTP
/*    val results = enforceOTPForUsers(keycloak, inactiveUsers)

    println("\nOTP Enforcement Results:")
    println("-----------------------")
    results.foreach {
      case (username, true) => println(s"Successfully enforced OTP for: $username")
      case (username, false) => println(s"Failed to enforce OTP for: $username")
    }

    val successCount = results.count(_._2)
    println(s"\nSummary: Successfully enforced OTP for $successCount out of ${results.length} users")*/


//    exportInactiveUsers(inactiveUsers, "inactive_users_report.json")
//    println("Report exported to inactive_users_report.json")

  } catch {
    case e: Exception =>
      println(s"Error: ${e.getMessage}")
      e.printStackTrace()
  } finally {
    keycloak.close()
  }
}