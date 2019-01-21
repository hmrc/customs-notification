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

package uk.gov.hmrc.customs.notification.services.config

import java.util.concurrent.TimeUnit

import cats.implicits._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.api.common.config.{ConfigValidatedNelAdaptor, CustomsValidatedNel}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain._

import scala.concurrent.duration.{Duration, FiniteDuration}

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
class ConfigService @Inject()(configValidatedNel: ConfigValidatedNelAdaptor, logger: CdsLogger) extends CustomsNotificationConfig {

  private case class CustomsNotificationConfigImpl(maybeBasicAuthToken: Option[String],
                                                   notificationQueueConfig: NotificationQueueConfig,
                                                   googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig,
                                                   pushNotificationConfig: PushNotificationConfig,
                                                   pullExcludeConfig: PullExcludeConfig,
                                                   notificationMetricsConfig: NotificationMetricsConfig) extends CustomsNotificationConfig

  private val root = configValidatedNel.root

  private val config: CustomsNotificationConfig = {

    val authTokenInternalNel: CustomsValidatedNel[Option[String]] =
      configValidatedNel.root.maybeString("auth.token.internal")

    val notificationQueueConfigNel: CustomsValidatedNel[NotificationQueueConfig] =
      configValidatedNel.service("notification-queue").serviceUrl.map(NotificationQueueConfig.apply)

    val notificationMetricsConfigNel: CustomsValidatedNel[NotificationMetricsConfig] =
      configValidatedNel.service("customs-notification-metrics").serviceUrl.map(NotificationMetricsConfig.apply)

    val gaSenderUrl = configValidatedNel.service("google-analytics-sender").serviceUrl
    val gaTrackingId = root.string("googleAnalytics.trackingId")
    val gaClientId = root.string("googleAnalytics.clientId")
    val gaEventValue = root.string("googleAnalytics.eventValue")
    val gaEnabled= root.boolean("googleAnalytics.enabled")
    val validatedGoogleAnalyticsSenderConfigNel: CustomsValidatedNel[GoogleAnalyticsSenderConfig] = (
      gaSenderUrl, gaTrackingId, gaClientId, gaEventValue, gaEnabled
    ).mapN(GoogleAnalyticsSenderConfig)

    val internalClientIdsNel: CustomsValidatedNel[Seq[String]] =
      root.stringSeq("push.internal.clientIds")
    val pollingDelayNel: CustomsValidatedNel[FiniteDuration] =
      root.int("push.polling.delay.duration.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val pushLockDurationNel: CustomsValidatedNel[org.joda.time.Duration] =
      root.int("push.lock.duration.milliseconds").map(millis => org.joda.time.Duration.millis(millis))
    val maxFetchRecordsNel: CustomsValidatedNel[Int] =
      root.int("push.fetch.maxRecords")
    val pushNotificationConfig: CustomsValidatedNel[PushNotificationConfig] = (
      internalClientIdsNel,
      pollingDelayNel,
      pushLockDurationNel,
      maxFetchRecordsNel
    ).mapN(PushNotificationConfig)

    val emailUrlNel = configValidatedNel.service("email").serviceUrl
    val notificationsOlderMillisNel: CustomsValidatedNel[Int] =
      root.int("pull.exclude.older.milliseconds")
    val csIdsToExcludeNel: CustomsValidatedNel[Seq[String]] =
      root.stringSeq("pull.exclude.csIds")
    val pullExcludeEnabledNel: CustomsValidatedNel[Boolean] =
      root.boolean("pull.exclude.enabled")
    val emailAddressNel: CustomsValidatedNel[String] =
      root.string("pull.exclude.email.address")
    val pullExcludePollingDelayNel: CustomsValidatedNel[FiniteDuration] =
      root.int("pull.exclude.email.delay.duration.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS))
    val pullExcludePollingIntervalNel: CustomsValidatedNel[FiniteDuration] =
      root.int("pull.exclude.email.interval.duration.minutes").map(minutes => Duration(minutes, TimeUnit.MINUTES))
    val pullExcludeConfig: CustomsValidatedNel[PullExcludeConfig] = (
      pullExcludeEnabledNel,
      emailAddressNel,
      notificationsOlderMillisNel,
      csIdsToExcludeNel,
      emailUrlNel,
      pullExcludePollingDelayNel,
      pullExcludePollingIntervalNel
    ).mapN(PullExcludeConfig)

    val validatedConfig: CustomsValidatedNel[CustomsNotificationConfig] = (
      authTokenInternalNel,
      notificationQueueConfigNel,
      validatedGoogleAnalyticsSenderConfigNel,
      pushNotificationConfig,
      pullExcludeConfig,
      notificationMetricsConfigNel
    ).mapN(CustomsNotificationConfigImpl)

      /*
       * the fold below is also similar to how we handle the error/success cases for Play2 forms - again the underlying
       * FP principles are the same.
       */

    validatedConfig.fold({
      nel => // error case exposes nel (a NotEmptyList)
        val errorMsg = "\n" + nel.toList.mkString("\n")
        logger.error(errorMsg)
        throw new IllegalStateException(errorMsg)
        },
      config => config // success case exposes the value class
    )

  }

  override val maybeBasicAuthToken: Option[String] = config.maybeBasicAuthToken

  override val notificationQueueConfig: NotificationQueueConfig = config.notificationQueueConfig

  override val googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig = config.googleAnalyticsSenderConfig

  override val pushNotificationConfig: PushNotificationConfig = config.pushNotificationConfig

  override val pullExcludeConfig: PullExcludeConfig = config.pullExcludeConfig

  override val notificationMetricsConfig: NotificationMetricsConfig = config.notificationMetricsConfig

}
