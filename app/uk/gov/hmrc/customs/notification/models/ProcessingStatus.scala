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

package uk.gov.hmrc.customs.notification.models

import org.bson.BsonString
import org.mongodb.scala.bson.BsonValue
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{Failed, InProgress, PermanentlyFailed}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus as InternalStatus, ResultStatus as InternalResultStatus}

sealed trait ProcessingStatus {
  def name: String
  final def toBson: BsonValue = new BsonString(internalStatus.name)

  def 
  internalStatus: InternalStatus
}

object ProcessingStatus {
  case object Succeeded extends ProcessingStatus {
    val name = "Succeeded"
    val internalStatus: InternalResultStatus = InternalStatus.Succeeded
  }

  case object SavedToBeSent extends ProcessingStatus {
    val name = "SavedToBeSent"
    val internalStatus: InternalStatus = InProgress
  }

  case object FailedAndBlocked extends ProcessingStatus {
    val name = "FailedAndBlocked"
    val internalStatus: InternalResultStatus = PermanentlyFailed
  }

  case object FailedButNotBlocked extends ProcessingStatus {
    val name = "FailedButNotBlocked"
    val internalStatus: InternalResultStatus = Failed
  }
}