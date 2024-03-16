import sbt._

object Dependencies {
  private val keycloakVersion = "24.0.1"
  private val awsUtilsVersion = "0.1.146"

  lazy val kmsUtils =  "uk.gov.nationalarchives" %% "kms-utils" % awsUtilsVersion
  lazy val s3Utils =  "uk.gov.nationalarchives" %% "s3-utils" % awsUtilsVersion
  lazy val decoderUtils =  "uk.gov.nationalarchives" %% "decoders-utils" % awsUtilsVersion
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.25.11"
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.5.4"
  lazy val lambdaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.1"
  lazy val csvParser =  "io.github.zamblauskas" %% "scala-csv-parser" % "0.13.1"
  lazy val circeExtras =  "io.circe" %% "circe-generic-extras" % "0.14.3"
  lazy val keycloakCore  = "org.keycloak" % "keycloak-core" % keycloakVersion
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % keycloakVersion
  lazy val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.17.6"
  lazy val pureConfigCatsEffect = "com.github.pureconfig" %% "pureconfig-cats-effect" % "0.17.6"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.18"
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "2.0.12"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "3.0.1"
}
