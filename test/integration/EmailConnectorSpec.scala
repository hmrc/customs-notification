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
import uk.gov.hmrc.customs.notification.connectors.EmailConnector
import uk.gov.hmrc.customs.notification.domain.{Email, SendEmailRequest}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import util.EmailService
import util.ExternalServicesConfiguration.{Host, Port}

class EmailConnectorSpec extends IntegrationTestSpec
  with GuiceOneAppPerSuite
  with MockitoSugar
  with BeforeAndAfterAll
  with EmailService {

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override implicit lazy val app: Application = GuiceApplicationBuilder().configure(Map(
      "microservice.services.email.host" -> Host,
      "microservice.services.email.port" -> Port,
      "microservice.services.email.context" -> "/hmrc/email"
    )).build()

  trait Setup {
    val mockHttpClient: HttpClient = mock[HttpClient]

    val sendEmailRequest = SendEmailRequest(List(Email("some-email@address.com")), "some-template-id",
      Map("parameters" -> "some-parameter"), force = false)

    implicit val hc = HeaderCarrier()
    lazy val connector: EmailConnector = app.injector.instanceOf[EmailConnector]
  }

  "EmailConnector" should {
    "successfully email" in new Setup {
//      stubFor(post(urlEqualTo("/hmrc/email")).willReturn(aResponse().withStatus(ACCEPTED)))

      await(connector.send(sendEmailRequest))

      verifyEmailServiceWasCalled()
    }
  }
}
