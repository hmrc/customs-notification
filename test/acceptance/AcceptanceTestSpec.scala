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

package acceptance

import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import util.ExternalServicesConfig

trait AcceptanceTestSpec extends FeatureSpec with GivenWhenThen with GuiceOneAppPerSuite
   with BeforeAndAfterAll with BeforeAndAfterEach with Eventually {

  private val Wait = 1

  override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = Span(Wait, Seconds))

  val acceptanceTestConfigs: Map[String, Any] = Map(
    "auth.token.internal" -> "YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=",
    "microservice.services.public-notification.host" -> ExternalServicesConfig.Host,
    "microservice.services.public-notification.port" -> ExternalServicesConfig.Port,
    "microservice.services.public-notification.context" -> ExternalServicesConfig.PublicNotificationServiceContext,
    "microservice.services.api-subscription-fields.host" -> ExternalServicesConfig.Host,
    "microservice.services.api-subscription-fields.port" -> ExternalServicesConfig.Port,
    "microservice.services.api-subscription-fields.context" -> ExternalServicesConfig.ApiSubscriptionFieldsServiceContext,
    "auditing.enabled" -> false
  )

  override implicit lazy val app: Application = new GuiceApplicationBuilder().configure(acceptanceTestConfigs).build()

}
