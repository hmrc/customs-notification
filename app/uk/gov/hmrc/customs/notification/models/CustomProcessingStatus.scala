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
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, ResultStatus}

sealed trait CustomProcessingStatus {
  val name: String

  def convertToBson: BsonValue

  def convertToHmrcProcessingStatus: ProcessingStatus
}

//The name value doesn't match the name of the object because we don't want to break the DB
//However, the names of the objects are more accurate descriptions of the statuses
//There is work remaining in order to fully decouple from the HMRC library version of ProcessingStatus which is not apt for our purposes
//But I didn't want to break Mongo in the scope of this ticket (DCWL-1627)
case object SavedToBeSent extends CustomProcessingStatus {
  override val name: String = "in-progress"
  val convertToBson: BsonValue = new BsonString(name)
  val convertToHmrcProcessingStatus: ProcessingStatus = InProgress
}

case object FailedButNotBlocked extends CustomProcessingStatus {
  override val name = "failed"
  val convertToBson: BsonValue = new BsonString(name)
  val convertToHmrcProcessingStatus: ResultStatus = Failed
}

case object FailedAndBlocked extends CustomProcessingStatus {
  override val name = "permanently-failed"
  val convertToBson: BsonValue = new BsonString(name)
  val convertToHmrcProcessingStatus: ResultStatus = PermanentlyFailed
}