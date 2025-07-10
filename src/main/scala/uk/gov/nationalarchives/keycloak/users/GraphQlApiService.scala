package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import graphql.codegen.types.ConsignmentFilters
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.Future

class GraphQlApiService(keycloak: KeycloakUtils, getConsignmentsClient: GraphQLClient[gcs.Data, gcs.Variables])(implicit keycloakDeployment: TdrKeycloakDeployment, backend: SttpBackend[Identity, Any]) {
  implicit class FutureUtils[T](f: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(f))
  }

  implicit val addOrUpdateBulkFileMetadataInputDecoder: Decoder[ConsignmentFilters] = deriveDecoder
  implicit val ucslvVariablesDecoder: Decoder[gcs.Variables] = deriveDecoder

  def getConsignments(config: Config.ConsignmentApi, userId: UUID): IO[gcs.Consignments] = for {
    token <- keycloak.serviceAccountToken(config.client, config.secret).toIO
    result <- getConsignmentsClient.getResult(token, gcs.document, Some(gcs.Variables(limit = 10, None, currentPage = Some(0), consignmentFiltersInput = Some(ConsignmentFilters(Some(userId), consignmentType = None))))).toIO
    data <- IO.fromOption(result.data)(new RuntimeException(result.errors.headOption.get.message))
  } yield data.consignments
}

object GraphQlApiService {
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  def apply(keycloak: KeycloakUtils, getConsignmentsClient: GraphQLClient[gcs.Data, gcs.Variables])(implicit keycloakDeployment: TdrKeycloakDeployment, backend: SttpBackend[Identity, Any]) = new GraphQlApiService(keycloak, getConsignmentsClient)(keycloakDeployment, backend)
}
