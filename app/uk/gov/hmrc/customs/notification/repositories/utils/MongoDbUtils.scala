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

package uk.gov.hmrc.customs.notification.repositories.utils

import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

private[repositories] object MongoDbUtils {
  /** TTL index must use this name so [[uk.gov.hmrc.mongo.MongoUtils.getTtlState]] can find it */
  private val TtlName = "expireAfterSeconds"

  def ttlIndexFor(fieldName: String, lifetime: FiniteDuration): IndexModel = {
    IndexModel(
      keys = Indexes.ascending(fieldName),
      indexOptions = IndexOptions()
        .name(MongoDbUtils.TtlName)
        .unique(false)
        .expireAfter(lifetime.toSeconds, TimeUnit.SECONDS)
    )
  }

}
