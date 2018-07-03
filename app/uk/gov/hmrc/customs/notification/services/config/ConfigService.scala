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

package uk.gov.hmrc.customs.notification.services.config

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.api.common.config.ConfigValidationNelAdaptor
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, GoogleAnalyticsSenderConfig, NotificationQueueConfig, PushNotificationConfig}

import scalaz._
import scalaz.syntax.apply._
import scalaz.syntax.traverse._

/**
  * Responsible for reading the HMRC style Play2 configuration file, error handling, and de-serialising config into
  * the Scala model.
  *
  * Note this class is bound as an EagerSingleton to bind at application startup time - any exceptions will STOP the
  * application. If startup completes without any exceptions being thrown then dependent classes can be sure that
  * config has been loaded correctly.
  *
  * @param configValidationNel adaptor for config services that returns a `ValidationNel`
  */
@Singleton
class ConfigService @Inject()(configValidationNel: ConfigValidationNelAdaptor, logger: CdsLogger) extends CustomsNotificationConfig {

  private case class CustomsNotificationConfigImpl(maybeBasicAuthToken: Option[String],
                                                   notificationQueueConfig: NotificationQueueConfig,
                                                   googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig,
                                                   pushNotificationConfig: PushNotificationConfig) extends CustomsNotificationConfig

  private val root = configValidationNel.root

  private val config: CustomsNotificationConfig = {

    val authTokenInternalNel: ValidationNel[String, Option[String]] =
      configValidationNel.root.maybeString("auth.token.internal")

    val notificationQueueConfigNel: ValidationNel[String, NotificationQueueConfig] =
      configValidationNel.service("notification-queue").serviceUrl.map(NotificationQueueConfig.apply)


    val gaSenderUrl = configValidationNel.service("google-analytics-sender").serviceUrl
    val gaTrackingId = root.string("googleAnalytics.trackingId")
    val gaClientId = root.string("googleAnalytics.clientId")
    val gaEventValue = root.string("googleAnalytics.eventValue")

    val validatedGoogleAnalyticsSenderConfigNel: ValidationNel[String, GoogleAnalyticsSenderConfig] = (
      gaSenderUrl |@| gaTrackingId |@| gaClientId |@| gaEventValue
      ) (GoogleAnalyticsSenderConfig.apply)


    val pollingDelayInMillisecondsNel: ValidationNel[String, Int] =
      configValidationNel.root.int("push.polling.delay.duration.milliseconds")
    val pushLockDurationInMillisecondsNel: ValidationNel[String, Int] =
      configValidationNel.root.int("push.lock.duration.milliseconds")
    val pushLockRefreshDurationInMillisecondsNel: ValidationNel[String, Int] =
      configValidationNel.root.int("push.lock.refresh.duration.milliseconds")
    val pushNotificationConfig: ValidationNel[String, PushNotificationConfig] = (
      pollingDelayInMillisecondsNel |@|
        pushLockDurationInMillisecondsNel |@|
        pushLockRefreshDurationInMillisecondsNel
      )(PushNotificationConfig.apply)

    val validatedConfig: ValidationNel[String, CustomsNotificationConfig] =
      (authTokenInternalNel |@|
        notificationQueueConfigNel |@|
        validatedGoogleAnalyticsSenderConfigNel |@|
        pushNotificationConfig
        )(CustomsNotificationConfigImpl.apply)

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

  override val googleAnalyticsSenderConfig = config.googleAnalyticsSenderConfig

  override val pushNotificationConfig: PushNotificationConfig = config.pushNotificationConfig
}
