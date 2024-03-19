/*
 * Copyright 2024 HM Revenue & Customs
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
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.connectors.NotificationQueueConnector
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.http.Non2xxResponseException
import uk.gov.hmrc.http._
import util.ExternalServicesConfiguration.{Host, Port}
import util.TestData._
import util.{ExternalServicesConfiguration, NotificationQueueService, WireMockRunnerWithoutServer}

class NotificationQueueConnectorSpec extends IntegrationTestSpec
  with GuiceOneAppPerSuite
  with MockitoSugar
  with BeforeAndAfterAll
  with NotificationQueueService
  with WireMockRunnerWithoutServer {

  private lazy val connector = app.injector.instanceOf[NotificationQueueConnector]

  val incomingBearerToken = "some_client's_bearer_token"
  val incomingAuthToken = s"Bearer $incomingBearerToken"

  override protected def beforeAll(): Unit = {
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  override protected def afterAll(): Unit = {
    stopMockServer()
  }

  override implicit lazy val app: Application =
    GuiceApplicationBuilder().configure(Map(
      "auditing.enabled" -> false,
      "microservice.services.notification-queue.host" -> Host,
      "microservice.services.notification-queue.port" -> Port,
      "microservice.services.notification-queue.context" -> ExternalServicesConfiguration.NotificationQueueContext,
      "non.blocking.retry.after.minutes" -> 10
    )).build()

  "NotificationQueueConnector" should {

    "make a correct request with badgeId header" in {
      val notificationWithBadgeId = clientNotification()

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

    "make a correct request with notificationId header" in {
      val notificationWithNotificationId = clientNotification(withNotificationId = true)

      setupPullQueueServiceToReturn(CREATED, notificationWithNotificationId)

      await(postToQueue(notificationWithNotificationId))

      verifyPullQueueServiceWasCalledWith(notificationWithNotificationId)
    }
    
    "make a correct request when notificationId is not provided" in {
      val notificationWithoutNotificationId = clientNotification(withNotificationId = false)

      setupPullQueueServiceToReturn(CREATED, notificationWithoutNotificationId)

      await(postToQueue(clientNotification(withNotificationId = false)))

      verifyPullQueueServiceWasCalledWith(notificationWithoutNotificationId)
    }

    "make a correct request with correlationId header" in {
      val notificationWithCorrelationId = clientNotification(withCorrelationId = true)

      setupPullQueueServiceToReturn(CREATED, notificationWithCorrelationId)

      await(postToQueue(notificationWithCorrelationId))

      verifyPullQueueServiceWasCalledWith(notificationWithCorrelationId)
    }

    "make a correct request when correlationId is not provided" in {
      val notificationWithoutCorrelationId = clientNotification(withCorrelationId = false)

      setupPullQueueServiceToReturn(CREATED, notificationWithoutCorrelationId)

      await(postToQueue(clientNotification(withCorrelationId = false)))

      verifyPullQueueServiceWasCalledWith(notificationWithoutCorrelationId)
    }


    "return a failed future with wrapped HttpVerb NotFoundException when external service returns 404" in {
      setupPullQueueServiceToReturn(NOT_FOUND, clientNotification())

      verifyExpectedErrorCaught(NOT_FOUND)
    }

    "return a failed future with wrapped HttpVerbs BadRequestException when external service returns 400" in {
      setupPullQueueServiceToReturn(BAD_REQUEST, clientNotification())

      verifyExpectedErrorCaught(BAD_REQUEST)
    }

    "return a failed future with Upstream5xxResponse when external service returns 500" in {
      setupPullQueueServiceToReturn(INTERNAL_SERVER_ERROR, clientNotification())

      verifyExpectedErrorCaught(INTERNAL_SERVER_ERROR)
    }

    "return a failed future with wrapped HttpVerbs BadRequestException when it fails to connect the external service" in withoutWireMockServer {
      val caught = intercept[RuntimeException](await(postToQueue(clientNotification())))

      caught.getCause.getClass shouldBe classOf[BadGatewayException]
    }
  }

  private def postToQueue(request: ClientNotification) = {
    connector.enqueue(request)(HeaderCarrier())
  }

  private def verifyExpectedErrorCaught(expectedStatusCode: Int): Unit = {
    val thrown = intercept[RuntimeException](await(postToQueue(clientNotification())))
    thrown.getCause.getClass shouldBe classOf[Non2xxResponseException]
    thrown.getCause.asInstanceOf[Non2xxResponseException].responseCode shouldBe expectedStatusCode
  }
}
