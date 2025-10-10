import Dependencies._

ThisBuild / scalaVersion := "2.13.17"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "uk.gov.nationalarchives"
ThisBuild / organizationName := "nationalarchives"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-keycloak-user-management",
    libraryDependencies ++= Seq(
      awsSsm,
      kmsUtils,
      decoderUtils,
      s3Utils,
      snsUtils,
      catsEffect,
      keycloakCore,
      csvParser,
      keycloakAdminClient,
      kmsUtils,
      pureConfig,
      pureConfigCatsEffect,
      circe,
      circeParser,
      scalaTest % Test,
      slf4j,
      wiremock % Test,
      circeExtras % Test,
      authUtils,
      generatedGraphql,
      graphqlClient,
      mockito % Test
    ),
    Test / fork := true,
    Test / javaOptions += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf",
    assembly / assemblyJarName := "keycloak-user-management.jar",
    Test / parallelExecution := false,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs@_*) =>
        xs map {
          _.toLowerCase
        } match {
          case "services" :: _ =>
            MergeStrategy.filterDistinctLines
          case _ => MergeStrategy.discard
        }
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
