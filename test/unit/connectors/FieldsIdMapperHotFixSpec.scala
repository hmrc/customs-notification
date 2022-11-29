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

import org.mockito.ArgumentMatchers.any
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.controllers.{FieldsIdMapperHotFix, RequestMetaData}
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.UnitSpec

class FieldsIdMapperHotFixSpec extends UnitSpec with MockitoSugar {

  trait TestSetup {
    val logger = mock[NotificationLogger]
    implicit val md: RequestMetaData = mock[RequestMetaData]
    val fieldsIdMapperHotFix = new FieldsIdMapperHotFix(logger)
  }

  private def logVerifier(mockLogger: NotificationLogger, logLevel: String, logText: String): Unit = {
    println(s"[$logLevel] [$logText]")
    PassByNameVerifier(mockLogger, logLevel)
      .withByNameParam(logText)
      .withParamMatcher(any[HasId])
      .verify()
  }

  "FieldsIdMapperHotFix" should {

    "map old fields to new one 1" in {
      new TestSetup {
        val oldOne = "c86521a1-3bc3-4408-8ba4-4f51acaeb4d9"
        val newOne = FieldsIdMapperHotFix.workingFieldsId
        assert(newOne == fieldsIdMapperHotFix.translate(oldOne))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [$oldOne] to [$newOne].")
      }
    }

    "map old fields to new one 2" in {
      new TestSetup {
        val oldOne = "f964448d-7cf0-444e-9027-172162235dbf"
        val newOne = FieldsIdMapperHotFix.workingFieldsId
        assert(newOne == fieldsIdMapperHotFix.translate(oldOne))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [$oldOne] to [$newOne].")
      }
    }

    "map old fields to new one 3" in {
      new TestSetup {
        val oldOne = "8a2e1a95-6240-4256-9439-2ee0c59a16d6"
        val newOne = FieldsIdMapperHotFix.workingFieldsId
        assert(newOne == fieldsIdMapperHotFix.translate(oldOne))
        logVerifier(logger, "warn", s"FieldsIdMapperHotFix: translating fieldsId [$oldOne] to [$newOne].")
      }
    }

    "map other to itself" in {
      new TestSetup {
        val oldOne = "anyOtherString"
        val newOne = oldOne
        assert(newOne == fieldsIdMapperHotFix.translate(oldOne))
      }
    }
  }


}