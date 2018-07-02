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

package uk.gov.hmrc.customs.notification.repo

import javax.inject.{Inject, Singleton}

import reactivemongo.api.commands.WriteResult
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier

@Singleton
class ClientNotificationRepositoryErrorHandler @Inject() (notificationLogger: NotificationLogger) {

  private lazy implicit val emptyHC: HeaderCarrier = HeaderCarrier()

  def handleDeleteError(result: WriteResult, exceptionMsg: => String): Boolean = {
    handleError(result, databaseAltered, exceptionMsg)
  }

  def handleSaveError(writeResult: WriteResult, exceptionMsg: String, clientNotification: ClientNotification): Boolean = {

    def handleSaveError(result: WriteResult): Boolean =
      if (databaseAltered(result)) {
        true
      }
      else {
        throw new RuntimeException(exceptionMsg)
      }

    handleError(writeResult, handleSaveError, exceptionMsg)
  }

  private def handleError[T](result: WriteResult, f: WriteResult => T, exceptionMsg: => String): T = {
    result.writeConcernError.fold(f(result)) {
      errMsg => {
        val errorMsg = s"$exceptionMsg. $errMsg"
        notificationLogger.error(errorMsg)
        throw new RuntimeException(errorMsg)
      }
    }
  }

  private def databaseAltered(writeResult: WriteResult): Boolean = writeResult.n > 0

}
