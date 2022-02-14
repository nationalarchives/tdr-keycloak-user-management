package uk.gov.nationalarchives.keycloak.users

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext.Http
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.{S3BucketEntity, S3Entity, S3EventNotificationRecord, S3ObjectEntity}

import scala.jdk.CollectionConverters._

object LambdaRunner extends App {

  sendCsvLambdaRequest()
  sendApiLambdaRequest()

  def sendCsvLambdaRequest() = {
    // You will need to run this with credentials which have access to this bucket in the sandbox account
    val s3Entity = new S3Entity(null, new S3BucketEntity("tdr-create-bulk-keycloak-users-intg", null, null), new S3ObjectEntity("sample_csv_file.csv", null, null, null, null), null)
    val record = new S3EventNotificationRecord(null, null, null, null, null, null, null,s3Entity, null)
    val s3Event = new S3EventNotification(List(record).asJava)
      new CSVLambda().handleRequest(s3Event, null)
  }

  def sendApiLambdaRequest() = {
    val event = new APIGatewayV2HTTPEvent()
    event.setBody(
      """
        |{
        |  "users" : [
        |    {
        |      "userName": "bob",
        |      "email": "test@test.com",
        |      "password": "Password12",
        |      "firstName": "first",
        |      "lastName": "last"
        |    }
        |  ]
        |}
        |""".stripMargin)
    val requestContext = new RequestContext()
    val http = new Http()
    http.setMethod("POST")
    requestContext.setHttp(http)
    event.setRequestContext(requestContext)
    new ApiLambda().handleRequest(event, null)
  }
}
