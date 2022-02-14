package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import io.circe.generic.auto._
import uk.gov.nationalarchives.keycloak.users.Config._
import uk.gov.nationalarchives.keycloak.users.KeycloakUsers._
import io.circe.parser.decode
import scala.jdk.CollectionConverters._

class ApiLambda extends RequestHandler[APIGatewayV2HTTPEvent, String] {

  @Override
  override def handleRequest(input: APIGatewayV2HTTPEvent, context: Context): String = {
    input.getRequestContext.getHttp.getMethod match {
      case "DELETE" => delete(input)
      case "POST" => add(input)
      case method: String => IO.raiseError(new Exception(s"Invalid method $method"))
    }
  }.unsafeRunSync()

  private def delete(input: APIGatewayV2HTTPEvent): IO[String] = for {
    auth <- authFromConfig()
    output <- IO(deleteUser(auth, input.getPathParameters.asScala("userId")))
  } yield output

  private def add(input: APIGatewayV2HTTPEvent): IO[String] = for {
      auth <- authFromConfig()
      user <- IO.fromEither(decode[UserCredentials](input.getBody))
      output <- IO(createUsers(auth, List(user)))
    } yield output
}

object ApiLambda {
  case class Body(user: UserCredentials)
}
