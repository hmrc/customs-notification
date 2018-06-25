/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package integration

import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.http._
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util.{ExternalServicesConfiguration, NotificationQueueService}

class NotificationQueueConnectorSpec extends IntegrationTestSpec with GuiceOneAppPerSuite with MockitoSugar
  with BeforeAndAfterAll with NotificationQueueService {

  private lazy val connector = app.injector.instanceOf[NotificationQueueConnector]

  val incomingBearerToken = "some_client's_bearer_token"
  val incomingAuthToken = s"Bearer $incomingBearerToken"

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override implicit lazy val app: Application =
    GuiceApplicationBuilder().configure(Map(
      "auditing.enabled" -> false,
      "microservice.services.notification-queue.host" -> Host,
      "microservice.services.notification-queue.port" -> Port,
      "microservice.services.notification-queue.context" -> ExternalServicesConfiguration.NotificationQueueContext
    )).build()

  "PullNotificationServiceConnector" should {

    "make a correct request with badgeId header" in {
      val notificationWithBadgeId = clientNotification(withBadgeId = true)

      setupPullQueueServiceToReturn(CREATED, notificationWithBadgeId)

      await(postToQueue(notificationWithBadgeId))

      verifyPullQueueServiceWasCalledWith(notificationWithBadgeId)
    }

    "make a correct request when badgeId is not provided" in {
      val notificationWithoutBadgeId = clientNotification(withBadgeId = false)

      setupPullQueueServiceToReturn(CREATED, notificationWithoutBadgeId)

      await(postToQueue(clientNotification(withBadgeId = false)))

      verifyPullQueueServiceWasCalledWith(notificationWithoutBadgeId)
    }

    "return a failed future with wrapped HttpVerb NotFoundException when external service returns 404" in {
      setupPullQueueServiceToReturn(NOT_FOUND, clientNotification())

      val caught = intercept[Throwable](await(postToQueue(clientNotification())))

      caught.getCause.getClass shouldBe classOf[NotFoundException]
    }

    "return a failed future with wrapped HttpVerbs BadRequestException when external service returns 400" in {
      setupPullQueueServiceToReturn(BAD_REQUEST, clientNotification())

      val caught = intercept[RuntimeException](await(postToQueue(clientNotification())))

      caught.getCause.getClass shouldBe classOf[BadRequestException]
    }

    "return a failed future with Upstream5xxResponse when external service returns 500" in {
      setupPullQueueServiceToReturn(INTERNAL_SERVER_ERROR, clientNotification())

      intercept[Upstream5xxResponse](await(postToQueue(clientNotification())))
    }

    "return a failed future with wrapped HttpVerbs BadRequestException when it fails to connect the external service" in withoutWireMockServer {
      val caught = intercept[RuntimeException](await(postToQueue(clientNotification())))

      caught.getCause.getClass shouldBe classOf[BadGatewayException]
    }
  }

  private def postToQueue(request: ClientNotification) = {
    connector.enqueue(request)
  }
}
