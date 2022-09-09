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

package uk.gov.hmrc.customs.notification.repo.helpers
import org.mongodb.scala.model.Indexes.{compoundIndex, descending}
import org.mongodb.scala.model.{IndexModel, IndexOptions}

import java.util.concurrent.TimeUnit
object NotificationWorkItemIndexes {

  private val WORK_ITEM_STATUS = NotificationWorkItemFields.workItemFields.status
  private val WORK_ITEM_UPDATED_AT = NotificationWorkItemFields.workItemFields.updatedAt
  val TTL_INDEX_NAME = "createdAt-ttl-index"

  def ttlIndex(expiresInSeconds: Int): IndexModel = {
    IndexModel(
      keys = descending("createdAt"),
      indexOptions = IndexOptions()
        .name(TTL_INDEX_NAME)
        .unique(false)
        .expireAfter(expiresInSeconds, TimeUnit.SECONDS)
    )
  }

  val clientIdIndex: IndexModel = {
    IndexModel(
      keys = descending("clientNotification.clientId"),
      indexOptions = IndexOptions()
        .name("clientNotification-clientId-index")
        .unique(false)
    )
  }

  val clientIdStatusIndex: IndexModel = {
    IndexModel(
      keys = compoundIndex(
        descending("clientNotification.clientId"),
        descending(WORK_ITEM_STATUS)
      ),
      indexOptions = IndexOptions()
        .name(s"clientId-$WORK_ITEM_STATUS-index")
        .unique(false)
    )
  }

  val statusUpdatedAtIndex: IndexModel = {
    IndexModel(
      keys = compoundIndex(
        descending(WORK_ITEM_STATUS),
        descending(WORK_ITEM_UPDATED_AT)
      ),
      indexOptions = IndexOptions()
        .name(s"$WORK_ITEM_STATUS-$WORK_ITEM_UPDATED_AT-index")
        .unique(false)
    )
  }

  val notificationIdStatusIndex: IndexModel = {
    IndexModel(
      keys = compoundIndex(
        descending("clientNotification._id"),
        descending(WORK_ITEM_STATUS)
      ),
      indexOptions = IndexOptions()
        .name(s"csId-$WORK_ITEM_STATUS-index")
        .unique(false)
    )
  }

  def indexes(ttl: Int): Seq[IndexModel] = Seq(
    ttlIndex(ttl),
    clientIdIndex,
    clientIdStatusIndex,
    statusUpdatedAtIndex,
    notificationIdStatusIndex
  )


}
