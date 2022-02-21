import Dependencies._

ThisBuild / scalaVersion := "2.13.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "uk.gov.nationalarchives"
ThisBuild / organizationName := "nationalarchives"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-keycloak-user-management",
    resolvers ++= Seq[Resolver](
      "TDR Releases" at "s3://tdr-releases-mgmt"
    ),
    libraryDependencies ++= Seq(
      awsUtils,
      catsEffect,
      keycloakCore,
      csvParser,
      keycloakAdminClient,
      kmsUtils,
      pureConfig,
      pureConfigCatsEffect,
      s3Mock % Test,
      scalaTest % Test,
      slf4j,
      wiremock % Test,
      circeExtras % Test
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