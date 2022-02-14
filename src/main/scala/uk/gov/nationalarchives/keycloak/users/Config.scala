package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import uk.gov.nationalarchives.aws.utils.Clients.kms
import uk.gov.nationalarchives.aws.utils.KMSUtils

object Config {

  def authFromConfig(): IO[Auth] = ConfigSource.default.loadF[IO, Configuration].map(config => {
    val kmsUtils = KMSUtils(kms(config.kms.endpoint), Map("LambdaFunctionName" -> config.function.name))
    Auth(kmsUtils.decryptValue(config.auth.url),
      secret = kmsUtils.decryptValue(config.auth.secret),
      client = config.auth.client
    )
  })

  case class LambdaFunction(name: String)
  case class Kms(endpoint: String)
  case class Auth(url: String, secret: String, client: String)
  case class Configuration(auth: Auth, function: LambdaFunction, kms: Kms)
}
