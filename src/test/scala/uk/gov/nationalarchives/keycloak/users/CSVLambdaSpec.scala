package uk.gov.nationalarchives.keycloak.users

import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification._
import com.github.tomakehurst.wiremock.WireMockServer
import io.findify.s3mock.S3Mock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._
import uk.gov.nationalarchives.keycloak.users.LambdaSpecUtils._

import java.net.URI
import java.nio.file.Path
import java.util.UUID
import scala.jdk.CollectionConverters._

class CSVLambdaSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  val s3Api: S3Mock = S3Mock(port = 8003, dir = "/tmp/s3")
  val wiremockAuthServer = new WireMockServer(9002)
  val wiremockSsmServer = new WireMockServer(9003)
  val lambdaSpecUtils = new LambdaSpecUtils(wiremockAuthServer, wiremockSsmServer)

  override def beforeAll(): Unit = {
    super.beforeAll()
    s3Api.start
    lambdaSpecUtils.setupSsmServer()
    lambdaSpecUtils.wiremockKmsEndpoint.start()
    wiremockAuthServer.start()
    wiremockSsmServer.start()
    s3Client.createBucket(CreateBucketRequest.builder.bucket("test").build())
  }

  override def afterAll(): Unit = {
    super.afterAll()
    lambdaSpecUtils.wiremockKmsEndpoint.stop()
    wiremockAuthServer.stop()
    wiremockSsmServer.stop()
  }

  def s3Client: S3Client = S3Client.builder
    .region(Region.EU_WEST_2)
    .endpointOverride(URI.create("http://localhost:8003/"))
    .build()

  def uploadCsv(csvName: String): PutObjectResponse = {
    val csvPath: Path = Path.of(getClass.getResource(s"/csv/$csvName").getPath)
    s3Client.putObject(PutObjectRequest.builder.bucket("test").key(csvName).build(), csvPath)
  }

  def getS3Event(csvName: String): S3EventNotification = {
    val entity = new S3ObjectEntity(csvName, null, null, null, null)
    val bucket = new S3BucketEntity("test", null, null)
    val s3Entity = new S3Entity(null, bucket, entity, null)
    val record = new S3EventNotificationRecord(null, null, null, null, null, null, null,s3Entity, null)
    new S3EventNotification(List(record).asJava)
  }

  "handleRequest" should "return the user ID from Keycloak" in {
    val csvName = "password_supplied.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    val response = new CSVLambda().handleRequest(s3Event, null)
    response should equal(userId)
  }

  "handleRequest" should "create a user with a password if the password is set" in {
    val csvName = "password_supplied.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    val body = lambdaSpecUtils.userCreateCalls.head.getRequest.getBodyAsString
    val request: TestUserRequest = lambdaSpecUtils.testRequest(body)
    request.credentials.size should equal(1)
    request.credentials.head.`type` should equal("password")
    request.credentials.head.value should equal("Password12")
  }

  "handleRequest" should "not create credentials if the password is not set" in {
    val csvName = "password_not_supplied.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    val body = lambdaSpecUtils.userCreateCalls.head.getRequest.getBodyAsString
    val request: TestUserRequest = lambdaSpecUtils.testRequest(body)
    request.credentials.size should equal(0)
  }

  "handleRequest" should "not send an email if sendEmail is missing" in {
    val csvName = "send_email_missing.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(0)
  }

  "handleRequest" should "not send an email if sendEmail is false" in {
    val csvName = "send_email_false.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(0)
  }

  "handleRequest" should "send an email if sendEmail is true" in {
    val csvName = "send_email_true.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(1)
  }

  "handleRequest" should "send one email if sendEmail is true for one row and false for another" in {
    val csvName = "send_email_one_true.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    lambdaSpecUtils.sendEmailCalls(userId).size should equal(1)
  }

  "handleRequest" should "process multiple rows correctly" in {
    val csvName = "multiple_rows.csv"
    uploadCsv(csvName)
    val s3Event = getS3Event(csvName)
    val userId = UUID.randomUUID().toString
    lambdaSpecUtils.setupAuthServer(userId)

    new CSVLambda().handleRequest(s3Event, null)

    val requests: List[TestUserRequest] = lambdaSpecUtils.userCreateCalls.map(r => lambdaSpecUtils.testRequest(r.getRequest.getBodyAsString))
    for {
      x <- 1 until 4
    } yield {
      val email = s"test$x@test.com"
      val userOpt = requests.find(_.email == email)
      userOpt.isDefined should equal(true)
      val user = userOpt.get
      user.username should equal(email)
      user.firstName should equal(s"First$x Name$x")
      user.lastName should equal(s"Last$x Name$x")
      user.groups.min should equal(s"/transferring_body_user/Mock $x Department")
      user.groups.max should equal(s"/user_type/standard${x}_user")
    }
  }
}
