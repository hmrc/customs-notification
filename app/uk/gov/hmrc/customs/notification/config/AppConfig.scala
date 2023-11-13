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

package uk.gov.hmrc.customs.notification.config

import cats.data.Validated
import cats.implicits._
import uk.gov.hmrc.customs.api.common.config.{ConfigValidatedNelAdaptor, CustomsValidatedNel, ServiceConfigProvider}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.AppConfig.validateUrl
import uk.gov.hmrc.customs.notification.models.ClientId

import java.net.{URI, URL}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._

@Singleton
class BasicAuthTokenConfig @Inject()(appConfig: AppConfig) {
  val token: String = appConfig.basicAuthToken
}

@Singleton
class SendNotificationConfig @Inject()(appConfig: AppConfig) {
  val internalClientIds: Set[ClientId] = appConfig.notificationConfig.internalClientIds
  val nonBlockingRetryAfterMinutes: Int = appConfig.notificationConfig.nonBlockingRetryAfterMinutes
  val pullUrl: URL = appConfig.pullQueueUrl
}

@Singleton
class ApiSubscriptionFieldsUrlConfig @Inject()(appConfig: AppConfig) {
  val url: URL = appConfig.apiSubscriptionFieldsUrl
}
@Singleton
class MetricsUrlConfig @Inject()(appConfig: AppConfig) {
  val url: URL = appConfig.metricsUrl
}

case class CustomsNotificationConfig(basicAuthToken: String,
                                     pullQueueUrl: URL,
                                     apiSubscriptionFieldsUrl: URL,
                                     notificationConfig: NotificationConfig,
                                     notificationMetricsUrl: URL,
                                     unblockPollerConfig: UnblockPollerConfig)


case class NotificationConfig(internalClientIds: Set[ClientId],
                              ttlInSeconds: Int,
                              retryPollerEnabled: Boolean,
                              retryPollerInterval: FiniteDuration,
                              retryPollerAfterFailureInterval: FiniteDuration,
                              retryPollerInProgressRetryAfter: FiniteDuration,
                              retryPollerInstances: Int,
                              nonBlockingRetryAfterMinutes: Int,
                              hotFixTranslates: Seq[String] = Seq("old:new"))

case class UnblockPollerConfig(pollerEnabled: Boolean, pollerInterval: FiniteDuration)

/**
 * Responsible for reading the HMRC style Play2 configuration file, error handling, and de-serialising config into
 * the Scala model.
 *
 * Note this class is bound as an EagerSingleton to bind at application startup time - any exceptions will STOP the
 * application. If startup completes without any exceptions being thrown then dependent classes can be sure that
 * config has been loaded correctly.
 *
 * @param configValidatedNel adaptor for config services that returns a `ValidatedNel`
 */
@Singleton
class AppConfig @Inject()(configValidatedNel: ConfigValidatedNelAdaptor, logger: CdsLogger) {
  //This class/file is fairly ridiculous but as it is config I have left the bizarre Nel stuff alone
  private val config: CustomsNotificationConfig = {
    val root = configValidatedNel.root

    (configValidatedNel.root.string("auth.token.internal"),
      configValidatedNel.service("notification-queue").serviceUrl.andThen(validateUrl),
      configValidatedNel.service("api-subscription-fields").serviceUrl.andThen(validateUrl), (
      root.stringSeq("internal.clientIds").map(_.toSet.map(ClientId(_))),
      root.int("ttlInSeconds"),
      root.boolean("retry.poller.enabled"),
      root.int("retry.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS)),
      root.int("retry.poller.retryAfterFailureInterval.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS)),
      root.int("retry.poller.inProgressRetryAfter.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS)),
      root.int("retry.poller.instances"),
      root.int("non.blocking.retry.after.minutes"),
      root.stringSeq("hotfix.translates")
    ).mapN(NotificationConfig),
      configValidatedNel.service("customs-notification-metrics").serviceUrl.andThen(validateUrl),
      (root.boolean("unblock.poller.enabled"), root.int("unblock.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))).mapN(UnblockPollerConfig)
    ).mapN(CustomsNotificationConfig).fold({
      nel => // error case exposes nel (a NotEmptyList)
        val errorMsg = "\n" + nel.toList.mkString("\n")
        logger.error(errorMsg)
        throw new IllegalStateException(errorMsg)
    },
      config => config // success case exposes the value class
    )
    //The fold above is also similar to how we handle the error/success cases for Play2 forms, - again the underlying
    //FP principles are the same.
    // ***(No idea what this means but copied and pasted just in case makes sense to someone)***
  }

  val basicAuthToken: String = config.basicAuthToken
  val apiSubscriptionFieldsUrl: URL = config.apiSubscriptionFieldsUrl
  val pullQueueUrl: URL = config.pullQueueUrl
  val metricsUrl: URL = config.notificationMetricsUrl
  val notificationConfig: NotificationConfig = config.notificationConfig
  val unblockPollerConfig: UnblockPollerConfig = config.unblockPollerConfig
}

object AppConfig {
  def validateUrl(urlString: String): CustomsValidatedNel[URL] = {
    Validated.catchNonFatal(new URL(urlString)).leftMap[String](e => e.getMessage).toValidatedNel
  }
}
