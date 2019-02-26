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
import uk.gov.hmrc.customs.notification.domain.{UnblockPollingConfig, _}

import scala.concurrent.duration._

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
                                                   pushNotificationConfig: PushNotificationConfig,
                                                   pullExcludeConfig: PullExcludeConfig,
                                                   notificationMetricsConfig: NotificationMetricsConfig,
                                                   unblockPollingConfig: UnblockPollingConfig) extends CustomsNotificationConfig

  private val root = configValidatedNel.root

  private val config: CustomsNotificationConfig = {

    val authTokenInternalNel: CustomsValidatedNel[Option[String]] =
      configValidatedNel.root.maybeString("auth.token.internal")

    val notificationQueueConfigNel: CustomsValidatedNel[NotificationQueueConfig] =
      configValidatedNel.service("notification-queue").serviceUrl.map(NotificationQueueConfig.apply)

    val notificationMetricsConfigNel: CustomsValidatedNel[NotificationMetricsConfig] =
      configValidatedNel.service("customs-notification-metrics").serviceUrl.map(NotificationMetricsConfig.apply)

    val internalClientIdsNel: CustomsValidatedNel[Seq[String]] =
      root.stringSeq("push.internal.clientIds")
    val pollingEnabledNel: CustomsValidatedNel[Boolean] =
      root.boolean("push.polling.enabled")
    val pollingDelayNel: CustomsValidatedNel[FiniteDuration] =
      root.int("push.polling.delay.duration.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val pushLockDurationNel: CustomsValidatedNel[org.joda.time.Duration] =
      root.int("push.lock.duration.milliseconds").map(millis => org.joda.time.Duration.millis(millis))
    val maxFetchRecordsNel: CustomsValidatedNel[Int] =
      root.int("push.fetch.maxRecords")
    val ttlInSecondsNel: CustomsValidatedNel[Int] =
      root.int("ttlInSeconds")

    val retryPollerEnabledNel: CustomsValidatedNel[Boolean] =
      root.boolean("push.retry.enabled")
    val retryInitialPollingIntervalNel: CustomsValidatedNel[FiniteDuration] =
      root.int("push.retry.initialPollingInterval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val retryAfterFailureIntervalNel: CustomsValidatedNel[FiniteDuration] =
      root.int("push.retry.retryAfterFailureInterval.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS))
    val retryInProgressRetryAfterNel: CustomsValidatedNel[FiniteDuration] =
      root.int("push.retry.inProgressRetryAfter.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS))
    val retryPollerInstancesNel: CustomsValidatedNel[Int] =
      root.int("push.retry.poller.instances")

    val pushNotificationConfig: CustomsValidatedNel[PushNotificationConfig] = (
      internalClientIdsNel,
      pollingEnabledNel,
      pollingDelayNel,
      pushLockDurationNel,
      maxFetchRecordsNel,
      ttlInSecondsNel,

      retryPollerEnabledNel,
      retryInitialPollingIntervalNel,
      retryAfterFailureIntervalNel,
      retryInProgressRetryAfterNel,
      retryPollerInstancesNel
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

    val unblockPollingEnabledNel: CustomsValidatedNel[Boolean] =
      root.boolean("unblock.polling.enabled")
    val unblockPollingDelayNel: CustomsValidatedNel[FiniteDuration] =
      root.int("unblock.polling.delay.duration.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val unblockPollingConfigNel: CustomsValidatedNel[UnblockPollingConfig] =
      (unblockPollingEnabledNel,
        unblockPollingDelayNel
    ).mapN(UnblockPollingConfig)

    val validatedConfig: CustomsValidatedNel[CustomsNotificationConfig] = (
      authTokenInternalNel,
      notificationQueueConfigNel,
      pushNotificationConfig,
      pullExcludeConfig,
      notificationMetricsConfigNel,
      unblockPollingConfigNel
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

  override val pushNotificationConfig: PushNotificationConfig = config.pushNotificationConfig

  override val pullExcludeConfig: PullExcludeConfig = config.pullExcludeConfig

  override val notificationMetricsConfig: NotificationMetricsConfig = config.notificationMetricsConfig

  override val unblockPollingConfig: UnblockPollingConfig = config.unblockPollingConfig

}
