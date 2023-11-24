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


package integration

import javax.inject.{Inject, Singleton}
import com.google.inject.util.Modules
import play.api.libs.ws.{WSClient, WSRequest}
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.google.inject.{AbstractModule, Provider}
import org.scalatest.TestSuite
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api._
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientProvider, AhcWSModule, StandaloneAhcWSClient}
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClient
import uk.gov.hmrc.http.hooks.{RequestData, ResponseData}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.bootstrap.http.DefaultHttpClient
import util.TestData

import java.net.URL
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

trait IntegrationBaseSpec extends TestSuite
  with IntegrationRouter
  with GuiceOneServerPerSuite {

  implicit val system: ActorSystem = ActorSystem()
  implicit lazy val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  override def fakeApplication: Application = new GuiceApplicationBuilder()
    .configure(Map(
      "auditing.enabled" -> false,
      "metrics.enabled" -> false,
      "auth.token.internal" -> TestData.BasicAuthTokenValue,
      "internal.clientIds.0" -> TestData.InternalClientId.id,
      "microservice.services.public-notification.host" -> IntegrationRouter.TestHost,
      "microservice.services.public-notification.port" -> IntegrationRouter.TestPort,
      "microservice.services.public-notification.context" -> IntegrationRouter.ExternalPushUrlContext,
      "microservice.services.notification-queue.host" -> IntegrationRouter.TestHost,
      "microservice.services.notification-queue.port" -> IntegrationRouter.TestPort,
      "microservice.services.notification-queue.context" -> IntegrationRouter.PullQueueContext,
      "microservice.services.api-subscription-fields.host" -> IntegrationRouter.TestHost,
      "microservice.services.api-subscription-fields.port" -> IntegrationRouter.TestPort,
      "microservice.services.api-subscription-fields.context" -> IntegrationRouter.ApiSubsFieldsUrlContext,
      "microservice.services.customs-notification-metrics.host" -> IntegrationRouter.TestHost,
      "microservice.services.customs-notification-metrics.port" -> IntegrationRouter.TestPort,
      "microservice.services.customs-notification-metrics.context" -> IntegrationRouter.MetricsUrlContext,
      "ttlInSeconds" -> 42,
      "retry.poller.interval.milliseconds" -> 2000,
      "unblock.poller.interval.milliseconds" -> 2000,
      "retry.poller.retryAfterFailureInterval.seconds" -> 2,
      "non.blocking.retry.after.minutes" -> 1,
      "hotfix.translates.0" -> s"${TestData.OldClientSubscriptionId}:${TestData.NewClientSubscriptionId}",
      "mongodb.uri" -> "mongodb://localhost:27017/customs-notification"))
    .overrides(HttpClientSpyModule)
    .build()
}