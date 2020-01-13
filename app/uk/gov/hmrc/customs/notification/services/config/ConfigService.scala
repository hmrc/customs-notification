/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.customs.notification.domain.{UnblockPollerConfig, _}

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
                                                   notificationConfig: NotificationConfig,
                                                   notificationMetricsConfig: NotificationMetricsConfig,
                                                   unblockPollerConfig: UnblockPollerConfig) extends CustomsNotificationConfig

  private val root = configValidatedNel.root

  private val config: CustomsNotificationConfig = {

    val authTokenInternalNel: CustomsValidatedNel[Option[String]] =
      configValidatedNel.root.maybeString("auth.token.internal")

    val notificationQueueConfigNel: CustomsValidatedNel[NotificationQueueConfig] =
      configValidatedNel.service("notification-queue").serviceUrl.map(NotificationQueueConfig.apply)

    val notificationMetricsConfigNel: CustomsValidatedNel[NotificationMetricsConfig] =
      configValidatedNel.service("customs-notification-metrics").serviceUrl.map(NotificationMetricsConfig.apply)

    val internalClientIdsNel: CustomsValidatedNel[Seq[String]] =
      root.stringSeq("internal.clientIds")

    val ttlInSecondsNel: CustomsValidatedNel[Int] =
      root.int("ttlInSeconds")

    val retryPollerEnabledNel: CustomsValidatedNel[Boolean] =
      root.boolean("retry.poller.enabled")
    val retryPollerIntervalNel: CustomsValidatedNel[FiniteDuration] =
      root.int("retry.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val retryPollerAfterFailureIntervalNel: CustomsValidatedNel[FiniteDuration] =
      root.int("retry.poller.retryAfterFailureInterval.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS))
    val retryPollerInProgressRetryAfterNel: CustomsValidatedNel[FiniteDuration] =
      root.int("retry.poller.inProgressRetryAfter.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS))
    val retryPollerInstancesNel: CustomsValidatedNel[Int] =
      root.int("retry.poller.instances")
    val nonBlockingRetryAfterMinutesNel: CustomsValidatedNel[Int] =
      root.int("non.blocking.retry.after.minutes")

    val notificationConfig: CustomsValidatedNel[NotificationConfig] = (
      internalClientIdsNel,
      ttlInSecondsNel,
      retryPollerEnabledNel,
      retryPollerIntervalNel,
      retryPollerAfterFailureIntervalNel,
      retryPollerInProgressRetryAfterNel,
      retryPollerInstancesNel,
      nonBlockingRetryAfterMinutesNel
    ).mapN(NotificationConfig)

    val unblockPollerEnabledNel: CustomsValidatedNel[Boolean] =
      root.boolean("unblock.poller.enabled")
    val unblockPollerIntervalNel: CustomsValidatedNel[FiniteDuration] =
      root.int("unblock.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val unblockPollerConfigNel: CustomsValidatedNel[UnblockPollerConfig] =
      (unblockPollerEnabledNel,
        unblockPollerIntervalNel
    ).mapN(UnblockPollerConfig)

    val validatedConfig: CustomsValidatedNel[CustomsNotificationConfig] = (
      authTokenInternalNel,
      notificationQueueConfigNel,
      notificationConfig,
      notificationMetricsConfigNel,
      unblockPollerConfigNel
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

  override val notificationConfig: NotificationConfig = config.notificationConfig

  override val notificationMetricsConfig: NotificationMetricsConfig = config.notificationMetricsConfig

  override val unblockPollerConfig: UnblockPollerConfig = config.unblockPollerConfig
}
