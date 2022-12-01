/*
 * Copyright 2022 HM Revenue & Customs
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

package unit.connectors

import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.controllers.{FieldsIdMapperHotFix, RequestMetaData}
import uk.gov.hmrc.customs.notification.domain.NotificationConfig
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.UnitSpec

class FieldsIdMapperHotFixSpec extends UnitSpec with MockitoSugar {

  trait TestSetup {
    val logger = mock[CdsLogger]
    implicit val md: RequestMetaData = mock[RequestMetaData]

    val mockConfigService = mock[NotificationConfig]
  }

  private def logVerifier(mockLogger: CdsLogger, logLevel: String, logText: String): Unit = {
    println(s"[$logLevel] [$logText]")
    PassByNameVerifier(mockLogger, logLevel)
      .withByNameParam(logText)
      .verify()
  }

  "FieldsIdMapperHotFix" should {

    "map old fields to new one with just one configured" in {
      new TestSetup {
        when(mockConfigService.hotFixOld).thenReturn("old")
        when(mockConfigService.hotFixNew).thenReturn("new")
        val fieldsIdMapperHotFix = new FieldsIdMapperHotFix(logger, mockConfigService)
        val oldOne = "old"
        val newOne = "new"
        assert(newOne == fieldsIdMapperHotFix.translate(oldOne))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [$oldOne] to [$newOne].")
      }
    }

    "map other to itself" in {
      new TestSetup {
        when(mockConfigService.hotFixOld).thenReturn("old")
        when(mockConfigService.hotFixNew).thenReturn("new")
        val fieldsIdMapperHotFix = new FieldsIdMapperHotFix(logger, mockConfigService)
        val oldOne = "anyOtherString"
        val newOne = oldOne
        assert(newOne == fieldsIdMapperHotFix.translate(oldOne))
      }
    }

    "work with more than one ids" in {
      new TestSetup {
        when(mockConfigService.hotFixOld).thenReturn(s"oldA,oldB,oldC")
        when(mockConfigService.hotFixNew).thenReturn("new")
        val fieldsIdMapperHotFix = new FieldsIdMapperHotFix(logger, mockConfigService)
        val newOne = "new"

        assert(newOne == fieldsIdMapperHotFix.translate("oldA"))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [oldA] to [$newOne].")
        assert(newOne == fieldsIdMapperHotFix.translate("oldB"))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [oldB] to [$newOne].")
        assert(newOne == fieldsIdMapperHotFix.translate("oldC"))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [oldC] to [$newOne].")
        //still work with others.
        assert("other" == fieldsIdMapperHotFix.translate("other"))
      }
    }
  }

}