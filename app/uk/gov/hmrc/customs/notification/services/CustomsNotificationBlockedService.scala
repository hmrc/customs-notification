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

package uk.gov.hmrc.customs.notification.services

import uk.gov.hmrc.customs.notification.domain.ClientId
import uk.gov.hmrc.customs.notification.logging.CdsLogger
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemRepo

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CustomsNotificationBlockedService @Inject() (logger: CdsLogger,
                                                   notificationWorkItemRepo: NotificationWorkItemRepo)
                                                  (implicit ec: ExecutionContext) {

  def blockedCount(clientId: ClientId): Future[Int] = {
    logger.debug(s"getting blocked count for clientId ${clientId.id}")
    notificationWorkItemRepo.blockedCount(clientId)
  }

  def deleteBlocked(clientId: ClientId): Future[Boolean] = {
    logger.debug(s"deleting blocked flags for clientId ${clientId.id}")
    notificationWorkItemRepo.deleteBlocked(clientId).map { updateCount =>
      if (updateCount == 0) {
        false
      } else {
        true
      }
    }
  }
}
