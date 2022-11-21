package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import uk.gov.nationalarchives.aws.utils.s3.S3Clients.s3
import uk.gov.nationalarchives.keycloak.users.Config._
import uk.gov.nationalarchives.keycloak.users.KeycloakUsers.{UserCredentials, createUsers}
import zamblauskas.csv.parser._

import scala.collection.mutable
import scala.io.Source
import scala.jdk.CollectionConverters._

class CSVLambda extends RequestHandler[S3EventNotification, String] {

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  override def handleRequest(input: S3EventNotification, context: Context): String = {
    for {
      auth <- authFromConfig()
      records <- IO(input.getRecords.asScala)
      recordUsers <- IO(parseUserInputs(records))
    } yield createUsers(auth, recordUsers)
  }.unsafeRunSync()

  private def parseUserInputs(records: mutable.Buffer[S3EventNotification.S3EventNotificationRecord]): List[UserCredentials] = {
    records.flatMap(record => {
      val key = record.getS3.getObject.getKey
      val bucket = record.getS3.getBucket.getName
      val responseStream = s3(ConfigFactory.load.getString("s3.endpoint")).getObject(GetObjectRequest.builder.bucket(bucket).key(key).build)
      val responseString = Source.fromInputStream(responseStream).mkString
      Parser.parse[UserCredentials](responseString) match {
        case Left(error) =>
          logger.warn(s"Error parsing CSV file ${error.message} ${error.lineNum}")
          Nil
        case Right(users) => users.toList
      }
    }).toList
  }
}
