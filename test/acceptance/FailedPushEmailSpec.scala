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

package acceptance

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, OptionValues}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.mongo.{MongoSpecSupport, ReactiveRepository}
import util.TestData._
import util._

import scala.concurrent.ExecutionContext.Implicits.global

class FailedPushEmailSpec  extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with EmailService
  with NotificationQueueService
  with TableDrivenPropertyChecks
  with PushNotificationService
  with MongoSpecSupport {

  private val repo = new ReactiveRepository[ClientNotification, BSONObjectID](
    collectionName = "notifications",
    mongo = app.injector.instanceOf[ReactiveMongoComponent].mongoConnector.db,
    domainFormat = ClientNotification.clientNotificationJF) {
  }

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(
    "push.polling.delay.duration.milliseconds" -> 100,
    "pull.exclude.enabled" -> true,
    "pull.exclude.email.address" -> "some.address@domain.com",
    "pull.exclude.email.delay.duration.seconds" -> 3,
    "pull.exclude.email.interval.duration.minutes" -> 30,
    "pull.exclude.older.milliseconds" -> 50,
    "pull.exclude.csIds" -> Seq(validClientSubscriptionId1String),
    "microservice.services.email.host" -> ExternalServicesConfiguration.Host,
    "microservice.services.email.port" -> ExternalServicesConfiguration.Port
  ).build()

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  override protected def beforeEach(): Unit = {
    setupEmailServiceToReturn(ACCEPTED)
    await(repo.insert(client1Notification1WithTimeReceived))
    await(repo.insert(client2Notification1WithTimeReceived))
    resetMockServer()
  }

  override protected def afterEach(): Unit = {
    resetMockServer()
    await(repo.drop)
  }

  feature("Push notifications warning email") {

    scenario("notification failed push") {
      Given("failed notifications are in database and a csid is configured to be excluded from push")

      When("scheduler queries database")
      info("automatically occurs when app starts")

      Then("a warning email is sent")
      verifyEmailServiceWasCalled()

      And("the pull queue does not receive the notification")
      verifyNotificationQueueServiceWasNotCalled()
    }
  }

}
