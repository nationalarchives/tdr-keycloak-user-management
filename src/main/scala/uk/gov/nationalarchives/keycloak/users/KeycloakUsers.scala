package uk.gov.nationalarchives.keycloak.users

import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import org.keycloak.representations.idm.{CredentialRepresentation, UserRepresentation}
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import uk.gov.nationalarchives.keycloak.users.Config.Auth

import jakarta.ws.rs.core.Response
import scala.jdk.CollectionConverters._

object KeycloakUsers {

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  private def keyCloakAdminClient(auth: Auth): Keycloak = KeycloakBuilder.builder()
    .serverUrl(auth.url)
    .realm("tdr")
    .clientId(auth.client)
    .clientSecret(auth.secret)
    .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
    .build()

  def deleteUser(auth: Auth, userId: String): String = {
    val client = keyCloakAdminClient(auth)
    val realm = client.realm(auth.realm)
    val user = realm.users()
    user.delete(userId)
    client.close()
    userId
  }

  def createUsers(auth: Auth, userCredentials: List[UserCredentials]): String = {
    val client = keyCloakAdminClient(auth)
    def createUser(userCredentials: UserCredentials) = {
      val realm = client.realm(auth.realm)
      val user = realm.users()
      val userRepresentation: UserRepresentation = new UserRepresentation

      val creds = userCredentials.password.map(password => {
        logger.info("Password found, creating credentials")
        val credentials: CredentialRepresentation = new CredentialRepresentation
        credentials.setTemporary(false)
        credentials.setType(CredentialRepresentation.PASSWORD)
        credentials.setValue(password)
        credentials
      }).toList.asJava

      userRepresentation.setUsername(userCredentials.email)
      userRepresentation.setFirstName(userCredentials.firstName)
      userRepresentation.setLastName(userCredentials.lastName)
      userRepresentation.setEmail(userCredentials.email)
      userRepresentation.setEnabled(true)
      userRepresentation.setCredentials(creds)

      val bodyUserGroups = userCredentials.body.map(value => s"/transferring_body_user/$value").toList
      val tnaUserTypes = List("metadata_viewer", "transfer_adviser")
      val userTypeGroups =
        userCredentials.userType.map(userType => {
          if (tnaUserTypes.exists(userType.contains)) {
            s"/user_type/tna_user/$userType"
          } else {
            s"/user_type/${userType}_user"
          }
        }).toList

      userRepresentation.setGroups((bodyUserGroups ::: userTypeGroups).asJava)

      val response: Response = user.create(userRepresentation)
      logger.info(s"Response status ${response.getStatus}")
      if(response.getStatus == 201) {
        val id = response.getLocation.getPath.replaceAll(".*/([^/]+)$", "$1")
        if(userCredentials.sendEmail.getOrElse(false)) {
          logger.info(s"Sending email for user $id")
          user.get(id).executeActionsEmail(List("CONFIGURE_TOTP", "UPDATE_PASSWORD").asJava)
        }
        id
      } else {
        val reason = response.getStatusInfo.getReasonPhrase
        logger.info(reason)
        reason
      }
    }
    val ids = userCredentials.map(createUser).mkString("\n")
    client.close()
    ids
  }

  case class UserCredentials(email: String,
                             password: Option[String] = None,
                             firstName: String,
                             lastName: String,
                             body: Option[String] = None,
                             userType: Option[String] = None,
                             sendEmail: Option[Boolean] = None
                            )
}
