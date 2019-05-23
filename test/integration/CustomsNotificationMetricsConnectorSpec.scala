/*
 * Copyright 2019 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.{BAD_REQUEST, INTERNAL_SERVER_ERROR, NOT_FOUND}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.CustomsNotificationMetricsConnector
import uk.gov.hmrc.http._
import util.CustomsNotificationMetricsTestData.ValidCustomsNotificationMetricsRequest
import util.ExternalServicesConfiguration.{Host, Port}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.{AuditService, CustomsNotificationMetricsService, ExternalServicesConfiguration}

class CustomsNotificationMetricsConnectorSpec extends IntegrationTestSpec with GuiceOneAppPerSuite with MockitoSugar
with BeforeAndAfterAll with CustomsNotificationMetricsService with AuditService {

  private lazy val connector = app.injector.instanceOf[CustomsNotificationMetricsConnector]
  private implicit val mockLogger: CdsLogger = mock[CdsLogger]

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach() {
    resetMockServer()
    setupAuditServiceToReturn()
    Mockito.reset(mockLogger)
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override implicit lazy val app: Application =
    GuiceApplicationBuilder(overrides = Seq(IntegrationTestModule(mockLogger).asGuiceableModule)).configure(Map(
      "auditing.consumer.baseUri.host" -> Host,
      "auditing.consumer.baseUri.port" -> Port,
      "auditing.enabled" -> true,
      "microservice.services.customs-notification-metrics.host" -> Host,
      "microservice.services.customs-notification-metrics.port" -> Port,
      "microservice.services.customs-notification-metrics.context" -> ExternalServicesConfiguration.CustomsNotificationMetricsContext
    )).build()

  "MetricsConnector" should {

    "make a correct request" in {
      setupCustomsNotificationMetricsServiceToReturn()

      val response: Unit = await(sendValidRequest())
      response shouldBe (())
      eventually(verifyAuditServiceWasNotCalled())
    }

    "return a failed future when external service returns 404" in {
      setupCustomsNotificationMetricsServiceToReturn(NOT_FOUND)

      intercept[RuntimeException](await(sendValidRequest())).getCause.getClass shouldBe classOf[NotFoundException]

      eventually(verifyAuditServiceWasNotCalled())
      //[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232]: Call to customs notification metrics service failed. url=http://localhost:11111/log-times httpError=404
      verifyCdsLoggerWarn("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231]: Call to customs notification metrics service failed. url=http://localhost:11111/log-times httpError=404", mockLogger)
    }

    "return a failed future when external service returns 400" in {
      setupCustomsNotificationMetricsServiceToReturn(BAD_REQUEST)

      intercept[RuntimeException](await(sendValidRequest())).getCause.getClass shouldBe classOf[BadRequestException]

      eventually(verifyAuditServiceWasNotCalled())
      verifyCdsLoggerWarn("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231]: Call to customs notification metrics service failed. url=http://localhost:11111/log-times httpError=400", mockLogger)
    }

    "return a failed future when external service returns 500" in {
      setupCustomsNotificationMetricsServiceToReturn(INTERNAL_SERVER_ERROR)

      intercept[Upstream5xxResponse](await(sendValidRequest()))

      eventually(verifyAuditServiceWasNotCalled())
      verifyCdsLoggerWarn("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231]: Call to customs notification metrics service failed. url=http://localhost:11111/log-times", mockLogger)
    }

    "return a failed future when fail to connect the external service" in {
      stopMockServer()

      intercept[RuntimeException](await(sendValidRequest())).getCause.getClass shouldBe classOf[BadGatewayException]

      startMockServer()
      verifyCdsLoggerWarn("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231]: Call to customs notification metrics service failed. url=http://localhost:11111/log-times httpError=502", mockLogger)
    }

  }

  private def sendValidRequest() = {
    connector.post(ValidCustomsNotificationMetricsRequest)
  }

  private def verifyCdsLoggerWarn(message: String, logger: CdsLogger): Unit = {
    PassByNameVerifier(logger, "warn")
      .withByNameParam(message)
      .withByNameParamMatcher(any[Throwable])
      .verify()
  }

}
