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

package unit.services

import org.mockito.ArgumentMatchers.{eq => ameq}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.connectors.{InternalPushConnector, PushNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain.PushNotificationConfig
import uk.gov.hmrc.customs.notification.services.OutboundSwitchService
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.MockitoPassByNameHelper.PassByNameVerifier

class OutboundSwitchServiceSpec extends UnitSpec with MockitoSugar{

  trait SetUp {
    val mockConfigService = mock[ConfigService]
    val mockPushNotificationConfig = mock[PushNotificationConfig]
    val mockExternalConnector = mock[PushNotificationServiceConnector]
    val mockInternalPushService = mock[InternalPushConnector]
    val mockLogger = mock[CdsLogger]

    val switcher = new OutboundSwitchService(mockConfigService, mockExternalConnector, mockInternalPushService, mockLogger)
  }

  "OutboundSwitchService" should {

    "route internally when config property push.internal.clientIds contains a matching clientId" in new SetUp {
      when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.internalClientIds).thenReturn(Seq(ClientIdStringOne))

      switcher.send(ClientIdOne, pnrOne)

      verifyZeroInteractions(mockExternalConnector)
      verify(mockInternalPushService).send(ameq(pnrOne))
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("[ConversationId=caca01f9-ec3b-4ede-b263-61b626dde231] About to push internally for clientId=ClientIdOne")
        .verify()
    }

    "route externally when config property push.internal.clientIds does not contains a matching clientId" in new SetUp {
      when(mockConfigService.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
      when(mockPushNotificationConfig.internalClientIds).thenReturn(Seq.empty)

      switcher.send(ClientIdOne, pnrOne)

      verify(mockExternalConnector).send(ameq(pnrOne))
      verifyZeroInteractions(mockInternalPushService)
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam("[ConversationId=caca01f9-ec3b-4ede-b263-61b626dde231] About to push externally for clientId=ClientIdOne")
        .verify()
    }

  }

}
