import sbt._

object Dependencies {
  private val keycloakVersion = "18.0.2"

  lazy val awsUtils =  "uk.gov.nationalarchives" %% "tdr-aws-utils" % "0.1.34"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.17.233"
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.3.14"
  lazy val lambdaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.1"
  lazy val csvParser =  "io.github.zamblauskas" %% "scala-csv-parser" % "0.13.1"
  lazy val circeExtras =  "io.circe" %% "circe-generic-extras" % "0.14.2"
  lazy val keycloakCore  = "org.keycloak" % "keycloak-core" % keycloakVersion
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % keycloakVersion
  lazy val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.17.1"
  lazy val pureConfigCatsEffect = "com.github.pureconfig" %% "pureconfig-cats-effect" % "0.17.1"
  lazy val kmsUtils = "software.amazon.awssdk" % "kms" % "2.17.209"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.13"
  lazy val s3Mock = "io.findify" %% "s3mock" % "0.2.6"
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "1.7.36"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "2.27.2"
}
