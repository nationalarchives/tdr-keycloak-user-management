package uk.gov.nationalarchives.keycloak.users

import cats.effect.unsafe.implicits.global
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import graphql.codegen.GetConsignments.getConsignments.Consignments.Edges.Node
import graphql.codegen.GetConsignments.getConsignments.Consignments.{Edges, PageInfo}
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import org.mockito.ArgumentMatchers._
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sangria.ast.Document
import sttp.client3._
import uk.gov.nationalarchives.tdr.GraphQLClient.Extensions
import uk.gov.nationalarchives.tdr.error.GraphQlError
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

class GraphQlApiServiceSpec extends AnyFlatSpec with MockitoSugar with Matchers {

  implicit val executionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val tdrKeycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment("authUrl", "realm", 60)

  val keycloakUtils: KeycloakUtils = mock[KeycloakUtils]
  val graphQlClient: GraphQLClient[gcs.Data, gcs.Variables] = mock[GraphQLClient[gcs.Data, gcs.Variables]]
  val config: Config.Reporting = mock[Config.Reporting]
  val service = new GraphQlApiService(keycloakUtils, graphQlClient)
  val userId: UUID = UUID.randomUUID()

  "getConsignments" should "should return consignments on success" in {
    val token = new BearerAccessToken("token")
    val consignments = gcs.Consignments(Some(
      List(Some(Edges(node = Node(consignmentid = Some(UUID.randomUUID()), consignmentReference = "TDR-ABC", consignmentType = Some("Judgment"), exportDatetime = Some(ZonedDateTime.now()), createdDatetime = Some(ZonedDateTime.now()), consignmentStatuses = Nil, totalFiles = 1),
        "cursor")))), PageInfo(hasNextPage = false, None), None)
    val data = gcs.Data(consignments)

    when(keycloakUtils.serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment]))
      .thenReturn(Future.successful(token))

    when(graphQlClient.getResult(any[BearerAccessToken], any[Document], any[Option[gcs.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]]))
      .thenReturn(Future.successful(GraphQlResponse[gcs.Data](Option(data), Nil)))

    val result = service.getConsignments(config, userId).unsafeRunSync()
    result shouldBe consignments
  }

  "getConsignments" should "throw an exception when a call to the api fails" in {
    val graphQlError = GraphQLClient.Error("Unable to get consignments", Nil, Nil, Some(Extensions(Some("NOT_AUTHORISED"))))
    val token = new BearerAccessToken("token")
    when(keycloakUtils.serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment]))
      .thenReturn(Future.successful(token))

    when(graphQlClient.getResult(any[BearerAccessToken], any[Document], any[Option[gcs.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]]))
      .thenReturn(Future.successful(GraphQlResponse[gcs.Data](None, List(GraphQlError(graphQlError)))))

    val exception = intercept[RuntimeException] {
      service.getConsignments(config, userId).unsafeRunSync()
    }
    exception.getMessage should equal(s"Unable to get consignments")
  }
}
