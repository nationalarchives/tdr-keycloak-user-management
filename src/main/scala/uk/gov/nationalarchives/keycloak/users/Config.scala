package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import com.typesafe.config.{ConfigFactory, Config => TypeSafeConfig}
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import uk.gov.nationalarchives.aws.utils.kms.KMSClients.kms
import uk.gov.nationalarchives.aws.utils.kms.KMSUtils

import java.net.URI

object Config {

  private val configFactory: TypeSafeConfig = ConfigFactory.load
  val authUrl: String = {
    val kmsUtils = KMSUtils(kms(configFactory.getString("kms.endpoint")), Map("LambdaFunctionName" -> configFactory.getString("function.name")))
    kmsUtils.decryptValue(configFactory.getString("auth.url"))
  }
  val apiUrl: String = configFactory.getString("consignment-api.url")

  def getClientSecret(secretPath: String, endpoint: String): String = {
    val ssmClient: SsmClient = SsmClient.builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.EU_WEST_2)
      .build()
    val getParameterRequest = GetParameterRequest.builder.name(secretPath).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value()
  }

  def authFromConfig(): IO[Auth] = ConfigSource.default.loadF[IO, Configuration].map(config => {
    val kmsUtils = KMSUtils(kms(config.kms.endpoint), Map("LambdaFunctionName" -> config.function.name))
    Auth(kmsUtils.decryptValue(config.auth.url),
      secret = getClientSecret(config.auth.secretPath, config.ssm.endpoint),
      config.auth.secretPath,
      client = config.auth.client,
      realm = config.auth.realm
    )
  })

  def reportingFromConfig(): IO[Reporting] = ConfigSource.default.loadF[IO, Configuration].map(config => {
    val kmsUtils = KMSUtils(kms(config.kms.endpoint), Map("LambdaFunctionName" -> config.function.name))
    Reporting(url = kmsUtils.decryptValue(config.reporting.url),
      client = config.reporting.client,
      secret = getClientSecret(config.reporting.secretPath, config.ssm.endpoint),
      config.reporting.secretPath,
      realm = config.reporting.realm
    )
  })

  def disableUsersFromConfig(): IO[DisableUsers] = ConfigSource.default.loadF[IO, Configuration].map(config => {
    DisableUsers(config.disableUsers.dryRun)
  })

  case class DisableUsers(dryRun: Boolean)
  case class LambdaFunction(name: String)
  case class Kms(endpoint: String)
  case class Ssm(endpoint: String)
  case class Auth(url: String, secret: String, secretPath: String, client: String, realm: String)
  case class Configuration(auth: Auth, reporting: Reporting, function: LambdaFunction, kms: Kms, ssm: Ssm, disableUsers: DisableUsers)
  case class Reporting(url: String, client: String, secret: String, secretPath: String, realm: String)
}
