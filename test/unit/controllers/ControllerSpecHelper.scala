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

package unit.controllers

import org.mockito.ArgumentMatchers.any
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.model.SeqOfHeader
import util.MockitoPassByNameHelper.PassByNameVerifier

trait ControllerSpecHelper {
  protected def verifyLog(method: String, message: String, mockLogger : NotificationLogger): Unit = {
    PassByNameVerifier(mockLogger, method)
      .withByNameParam(message)
      .withParamMatcher(any[HasId])
      .verify()
  }

  protected def verifyLogWithHeaders(method: String, message: String, mockLogger : NotificationLogger): Unit = {
    PassByNameVerifier(mockLogger, method)
      .withByNameParam(message)
      .withByNameParamMatcher(any[SeqOfHeader])
      .verify()
  }
}
