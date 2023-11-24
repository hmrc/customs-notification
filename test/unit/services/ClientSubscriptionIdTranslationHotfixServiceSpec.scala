/*
 * Copyright 2023 HM Revenue & Customs
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

import org.mockito.scalatest.{MockitoSugar, ResetMocksAfterEachTest}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.AppConfig
import uk.gov.hmrc.customs.notification.models.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.services.ClientSubscriptionIdTranslationHotfixService

import java.util.UUID

class ClientSubscriptionIdTranslationHotfixServiceSpec extends AnyWordSpec
  with Matchers
  with MockitoSugar
  with ResetMocksAfterEachTest {

  private val mockLogger = mock[CdsLogger]
  private val mockConfig = mock[AppConfig]
  private val service = new ClientSubscriptionIdTranslationHotfixService(mockLogger, mockConfig)

  "translate" when {
    "given a map" should {
      "map old IDs to new ones" in {
        val oldCsid = ClientSubscriptionId(UUID.fromString("00000000-2222-4444-2222-444444444444"))
        val newCsid = ClientSubscriptionId(UUID.fromString("00000000-5555-4444-3333-222222222222"))
        val map = Map(oldCsid -> newCsid)
        when(mockConfig.hotFixTranslates).thenReturn(map)

        service.translate(oldCsid) shouldBe newCsid
      }

      "map ID to itself if not found in map" in {
        val csid = ClientSubscriptionId(UUID.fromString("00000000-2222-4444-2222-444444444444"))
        val map = Map.empty[ClientSubscriptionId, ClientSubscriptionId]
        when(mockConfig.hotFixTranslates).thenReturn(map)

        service.translate(csid) shouldBe csid
      }
    }
  }
}



