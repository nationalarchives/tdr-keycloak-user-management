import sbt._

object Dependencies {
  private val awsUtilsVersion = "0.1.286"
  private val keycloakVersion = "26.3.2"
  private val circeVersion = "0.14.14"

  lazy val kmsUtils =  "uk.gov.nationalarchives" %% "kms-utils" % awsUtilsVersion
  lazy val s3Utils =  "uk.gov.nationalarchives" %% "s3-utils" % awsUtilsVersion
  lazy val decoderUtils =  "uk.gov.nationalarchives" %% "decoders-utils" % awsUtilsVersion
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.26.27"
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.6.3"
  lazy val lambdaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.1"
  lazy val csvParser =  "io.github.zamblauskas" %% "scala-csv-parser" % "0.13.1"
  lazy val circeExtras =  "io.circe" %% "circe-generic-extras" % "0.14.4"
  lazy val keycloakCore  = "org.keycloak" % "keycloak-core" % keycloakVersion
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % "26.0.6"
  lazy val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.17.9"
  lazy val pureConfigCatsEffect = "com.github.pureconfig" %% "pureconfig-cats-effect" % "0.17.9"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "2.0.17"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "3.0.1"
  lazy val circe = "io.circe" %% "circe-generic" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.252"
  lazy val generatedGraphql = "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.423"
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.244"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "2.0.0"
}
