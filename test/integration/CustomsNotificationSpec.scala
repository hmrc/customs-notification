package integration

import com.github.tomakehurst.wiremock.client.WireMock._
import integration.CustomsNotificationSpec._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.WsScalaTestClient
import play.api.http.MimeTypes
import play.api.libs.ws.WSClient
import play.api.test.Helpers._
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.customs.notification.repo.NotificationRepo
import uk.gov.hmrc.customs.notification.util.HeaderNames._
import util.IntegrationTest.Responses.apiSubscriptionFieldsOk
import util.IntegrationTest._
import util.TestData

class CustomsNotificationSpec extends AnyWordSpec
  with FutureAwaits
  with DefaultAwaitTimeout
  with BeforeAndAfterEach
  with Matchers
  with WsScalaTestClient
  with IntegrationBaseSpec {
  implicit val wsClient: WSClient = app.injector.instanceOf[WSClient]

  override def beforeEach(): Unit = {
    super.beforeEach()
    val repo = app.injector.instanceOf[NotificationRepo]
    await(repo.collection.drop().toFuture())
  }

  "customs-notification" when {
    "handling a request for a external push notification" should {
      "respond with 202 Accepted" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubInternalPush(ACCEPTED)

        val response = await(wsUrl(s"/customs-notification/notify")
          .withHttpHeaders(
            X_CLIENT_SUB_ID_HEADER_NAME -> TestData.OldClientSubscriptionId.toString,
            X_CONVERSATION_ID_HEADER_NAME -> TestData.ConversationId.toString,
            CONTENT_TYPE -> (MimeTypes.XML + "; charset=UTF-8"),
            ACCEPT -> MimeTypes.XML,
            AUTHORIZATION -> TestData.BasicAuthTokenValue,
            X_BADGE_ID_HEADER_NAME -> TestData.BadgeId,
            X_SUBMITTER_ID_HEADER_NAME -> TestData.SubmitterId,
            X_CORRELATION_ID_HEADER_NAME -> TestData.CorrelationId,
            ISSUE_DATE_TIME_HEADER_NAME -> TestData.IssueDateTime.toString)
          .post(TestData.ValidXml))

        response.status shouldBe ACCEPTED
      }

      "save the succeeded notification in the database" in {
        stubApiSubscriptionFieldsOk()
        stubMetricsAccepted()
        stubInternalPush(ACCEPTED)
      }

      "send the notification to the push service (customs-notification-gateway)" in {
        fail()
      }
    }

    "handling a request for an external push notification but receives a 500 error when sending" should {
      "return a 202 Accepted" in {
        fail()
      }

      "save the notification as FailedButNotBlocked in the database" in {
        fail()
      }

      "not attempt to send any more notifications for that client subscription ID during blocked period" in {
        fail()
      }
    }
  }
}

object CustomsNotificationSpec {

  private def stubApiSubscriptionFieldsOk(): Unit = {
    stubFor(get(urlMatching(s"$ApiSubsFieldsUrlContext/${TestData.NewClientSubscriptionId.toString}"))
      .willReturn(aResponse().withStatus(OK).withBody(apiSubscriptionFieldsOk)))
  }

  private def stubApiSubscriptionFields(responseStatus: Int): Unit = {
    stubFor(get(urlMatching(s"$ApiSubsFieldsUrlContext/${TestData.NewClientSubscriptionId.toString}"))
      .willReturn(aResponse().withStatus(responseStatus)))
  }

  private def stubMetricsAccepted(): Unit = {
    stubFor(post(urlMatching(s"$MetricsUrlContext"))
      .willReturn(aResponse().withStatus(ACCEPTED)))
  }

  private def stubInternalPush(responseStatus: Int): Unit = {
    stubFor(post(urlMatching(s"$ExternalPushUrlContext"))
      .willReturn(aResponse().withStatus(responseStatus)))
  }
}