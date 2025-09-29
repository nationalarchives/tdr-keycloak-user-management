package uk.gov.nationalarchives.keycloak.users

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import graphql.codegen.GetConsignments.getConsignments.Consignments.{Edges, PageInfo}
import graphql.codegen.GetConsignments.getConsignments.Consignments.Edges.Node
import graphql.codegen.GetConsignments.{getConsignments => gcs}
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.{RealmResource, UserResource, UsersResource}
import org.keycloak.representations.idm.{GroupRepresentation, UserRepresentation}
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDateTime, ZoneId, ZonedDateTime}
import java.util.UUID
import scala.jdk.CollectionConverters._

class InactiveKeycloakUsersUtilsSpec extends AnyFlatSpec with MockitoSugar with Matchers {

  val testRealm = "test-realm"
  val authConfig: Config.Auth = Config.Auth(url = "testUrl.com", secretPath = "secretPath", client = "client", realm = testRealm)
  val userId = "test-user-id"

  "findUsersCreatedBeforePeriod" should "return users created before the cutoff time" in {
    val keycloak = mock[Keycloak]
    val realmResource = mock[RealmResource]
    val usersResource = mock[UsersResource]
    val userResource = mock[UserResource]

    val now = LocalDateTime.now()
    val cutoff = now.minusDays(30).atZone(ZoneId.systemDefault()).toInstant.toEpochMilli
    val oldTimestamp = cutoff - 10000

    val user = new UserRepresentation()
    user.setId(userId)
    user.setEnabled(true)
    user.setCreatedTimestamp(oldTimestamp)

    val group = new GroupRepresentation()
    group.setName("judgment")

    when(keycloak.realm(testRealm)).thenReturn(realmResource)
    when(realmResource.users()).thenReturn(usersResource)
    when(usersResource.list(1, Int.MaxValue)).thenReturn(List(user).asJava)
    when(usersResource.get(userId)).thenReturn(userResource)
    when(userResource.groups()).thenReturn(List(group).asJava)
    when(userResource.toRepresentation()).thenReturn(user)

    val result = InactiveKeycloakUsersUtils.findUsersCreatedBeforePeriod(keycloak, authConfig, "judgment", 30)
    result should have size 1
    result.head.id shouldEqual userId
  }

  "userActivityOlderThanPeriod" should "correctly evaluate user inactivity" in {
    val now = ZonedDateTime.now()
    val activity = InactiveKeycloakUsersUtils.UserActivity("id1", now.minusDays(31))
    val result = InactiveKeycloakUsersUtils.userActivityOlderThanPeriod(activity, 30)
    result shouldBe true
  }

  "disableInactiveUsers" should "disable users based on the predicate" in {
    val keycloak = mock[Keycloak]
    val realmResource = mock[RealmResource]
    val usersResource = mock[UsersResource]
    val userResource = mock[UserResource]
    val userRep = mock[UserRepresentation]

    val user = InactiveKeycloakUsersUtils.UserActivity(userId, ZonedDateTime.now().minusDays(40))

    when(keycloak.realm(testRealm)).thenReturn(realmResource)
    when(realmResource.users()).thenReturn(usersResource)
    when(usersResource.get(userId)).thenReturn(userResource)
    when(userResource.toRepresentation()).thenReturn(userRep)

    val updatedUser = user.copy(isDisabled = true)

    val result = InactiveKeycloakUsersUtils.disableInactiveUsers(
      keycloak,
      authConfig,
      List(user),
      (_, _) => true,
      30
    ).unsafeRunSync()

    verify(userRep).setEnabled(false)
    verify(userResource).update(userRep)

    result should contain only updatedUser
  }

  "fetchLatestUserActivity" should "return the latest activity for a user" in {
    val user = InactiveKeycloakUsersUtils.User("u1")
    val now = ZonedDateTime.now()
    val earlier = now.minusDays(5)

    val consignmentData = gcs.Consignments(Some(
      List(Some(Edges(node = Node(consignmentid = Some(UUID.randomUUID()), consignmentReference = "TDR-ABC", consignmentType = Some("Judgment"), exportDatetime = Some(earlier), createdDatetime = Some(now), consignmentStatuses = Nil, totalFiles = 1),
        "cursor")))), PageInfo(hasNextPage = false, None), None)

    val ioConsignments = IO.pure(consignmentData)

    val result = InactiveKeycloakUsersUtils.fetchLatestUserActivity(user, ioConsignments).unsafeRunSync()

    result shouldBe defined
    result.get.userId shouldEqual user.id
    result.get.lastestActivityDatetime shouldEqual now
  }
}
