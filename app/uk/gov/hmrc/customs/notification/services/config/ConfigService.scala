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

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.customs.api.common.config.ConfigValidationNelAdaptor
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain._

import scala.concurrent.duration.{Duration, FiniteDuration}
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
                                                   pushNotificationConfig: PushNotificationConfig,
                                                   pullExcludeConfig: PullExcludeConfig) extends CustomsNotificationConfig

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
    val gaEnabled= root.boolean("googleAnalytics.enabled")

    val validatedGoogleAnalyticsSenderConfigNel: ValidationNel[String, GoogleAnalyticsSenderConfig] = (
      gaSenderUrl |@| gaTrackingId |@| gaClientId |@| gaEventValue |@| gaEnabled
      ) (GoogleAnalyticsSenderConfig.apply)

    val pollingDelayNel: ValidationNel[String, FiniteDuration] =
      root.int("push.polling.delay.duration.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS))
    val pushLockDurationNel: ValidationNel[String, org.joda.time.Duration] =
      root.int("push.lock.duration.milliseconds").map(millis => org.joda.time.Duration.millis(millis))
    val maxFetchRecordsNel: ValidationNel[String, Int] =
      root.int("push.fetch.maxRecords")
    val pushNotificationConfig: ValidationNel[String, PushNotificationConfig] = (
      pollingDelayNel |@|
        pushLockDurationNel |@|
        maxFetchRecordsNel
      )(PushNotificationConfig.apply)

    val notificationsOlderMillis: ValidationNel[String, Int] =
      root.int("pull.exclude.older.milliseconds")
    val csIdsToExclude: ValidationNel[String, Seq[String]] =
      root.stringSeq("pull.exclude.csIds")
    val pullExcludeEnabled: ValidationNel[String, Boolean] =
      root.boolean("pull.exclude.enabled")
    val emailAddresses: ValidationNel[String, Seq[String]] =
      root.stringSeq("pull.exclude.email.address")
    val pullExcludeConfig: ValidationNel[String, PullExcludeConfig] = (
      pullExcludeEnabled |@| emailAddresses |@| notificationsOlderMillis |@| csIdsToExclude
      )(PullExcludeConfig.apply)

    val validatedConfig: ValidationNel[String, CustomsNotificationConfig] =
      (authTokenInternalNel |@|
        notificationQueueConfigNel |@|
        validatedGoogleAnalyticsSenderConfigNel |@|
        pushNotificationConfig |@|
        pullExcludeConfig
        ) (CustomsNotificationConfigImpl.apply)

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

}
