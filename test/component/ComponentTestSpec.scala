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

package component

import org.mongodb.scala.model.Filters
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.repo.NotificationWorkItemMongoRepo
import util.ExternalServicesConfiguration

trait ComponentTestSpec extends AnyFeatureSpec
  with GivenWhenThen
  with GuiceOneAppPerSuite
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with Eventually
  with Matchers
  with OptionValues {

  private val Wait = 5

  override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = Span(Wait, Seconds))

  val acceptanceTestConfigs: Map[String, Any] = Map(
    "auth.token.internal" -> "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=",
    "microservice.services.public-notification.host" -> ExternalServicesConfiguration.Host,
    "microservice.services.public-notification.port" -> ExternalServicesConfiguration.Port,
    "microservice.services.public-notification.context" -> ExternalServicesConfiguration.PushNotificationServiceContext,
    "microservice.services.api-subscription-fields.host" -> ExternalServicesConfiguration.Host,
    "microservice.services.api-subscription-fields.port" -> ExternalServicesConfiguration.Port,
    "microservice.services.api-subscription-fields.context" -> ExternalServicesConfiguration.ApiSubscriptionFieldsServiceContext,
    "microservice.services.notification-queue.host" -> ExternalServicesConfiguration.Host,
    "microservice.services.notification-queue.port" -> ExternalServicesConfiguration.Port,
    "microservice.services.notification-queue.context" -> ExternalServicesConfiguration.NotificationQueueContext,
    "auditing.enabled" -> false,
    "mongodb.uri" -> "mongodb://localhost:27017/customs-notification",
    "metrics.jvm" -> false,
    "metrics.logback" -> false,
    "non.blocking.retry.after.minutes" -> 10
  )

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(acceptanceTestConfigs).build()

  val repository: NotificationWorkItemMongoRepo = app.injector.instanceOf[NotificationWorkItemMongoRepo]

  val collection = repository.collection

  def emptyCollection() = await(collection.deleteMany(Filters.exists("_id")).toFuture())
}
