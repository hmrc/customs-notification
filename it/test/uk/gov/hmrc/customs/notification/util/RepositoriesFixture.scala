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

package uk.gov.hmrc.customs.notification.util

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, Suite}
import org.scalatestplus.play.ServerProvider
import uk.gov.hmrc.customs.notification.repositories.{BlockedCsidRepository, NotificationRepository}

trait RepositoriesFixture extends BeforeAndAfterEach {
  self: Suite & ServerProvider & ScalaFutures & IntegrationPatience =>

  protected lazy val notificationRepo: NotificationRepository = app.injector.instanceOf[NotificationRepository]
  protected lazy val blockedCsidRepo: BlockedCsidRepository = app.injector.instanceOf[BlockedCsidRepository]
  protected def mockDateTimeService: MockDateTimeService
  protected def mockObjectIdService: MockObjectIdService

  override def beforeEach(): Unit = {
    notificationRepo.underlying.collection.drop().toFuture().futureValue
    blockedCsidRepo.collection.drop().toFuture().futureValue
    notificationRepo.underlying.ensureIndexes().futureValue
    blockedCsidRepo.ensureIndexes().futureValue
    mockDateTimeService.timeTravelToNow()
    mockObjectIdService.reset()
    super.beforeEach()
  }
}
