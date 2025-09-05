package uk.gov.nationalarchives.keycloak.users

import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.aws.utils.sns.SNSUtils
import uk.gov.nationalarchives.aws.utils.sns.SNSClients.sns
import uk.gov.nationalarchives.keycloak.users.Config.Sns
import uk.gov.nationalarchives.keycloak.users.InactiveKeycloakUsersLambda.LogInfo
import uk.gov.nationalarchives.keycloak.users.NotificationUtils.UsersDisabledEvent

class NotificationUtils(snsUtils: SNSUtils, snsConfig: Sns, environment: String) {
  def publishUsersDisabledEvent(count: Integer, logInfo: LogInfo): PublishResponse =
    snsUtils.publish(UsersDisabledEvent(environment, count, logInfo).asJson.toString(), snsConfig.notificationsTopicArn)
}

object NotificationUtils {
  def apply(snsConfig: Sns, environment: String): NotificationUtils = new NotificationUtils(SNSUtils(sns(snsConfig.endpoint)), snsConfig, environment)
  case class UsersDisabledEvent(environment: String, disabledUsersCount: Int, logInfo: LogInfo)
}
