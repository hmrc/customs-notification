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

package uk.gov.hmrc.customs.notification.repo

import javax.inject.{Inject, Singleton}
import reactivemongo.api.commands.WriteResult
import reactivemongo.play.json.commands.JSONFindAndModifyCommand.FindAndModifyResult
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.ClientNotification

@Singleton
class ClientNotificationRepositoryErrorHandler @Inject() (logger: CdsLogger) {

  def handleDeleteError(result: WriteResult, exceptionMsg: => String): Boolean = {
    handleError(result, databaseAltered, exceptionMsg)
  }

  private def handleError[T](result: WriteResult, f: WriteResult => T, exceptionMsg: => String): T = {
    result.writeConcernError.fold(f(result)) {
      errMsg => {
        val errorMsg = s"$exceptionMsg. $errMsg"
        logger.error(errorMsg)
        throw new RuntimeException(errorMsg)
      }
    }
  }

  def handleUpdateError(result: FindAndModifyResult, exceptionMsg: String, clientNotification: ClientNotification): Boolean = {
    result.lastError.fold(true) { lastError =>
      if (lastError.n > 0) {
        true
      } else {
        val errorMsg = lastError.err.fold(exceptionMsg)(errMsg => s"$exceptionMsg. $errMsg")
        logger.error(errorMsg)
        throw new RuntimeException(errorMsg)
      }
    }
  }

  private def databaseAltered(writeResult: WriteResult): Boolean = writeResult.n > 0

}
