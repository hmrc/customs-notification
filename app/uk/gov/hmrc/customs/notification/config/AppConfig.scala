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

import cats.implicits._
import uk.gov.hmrc.customs.api.common.config.ConfigValidatedNelAdaptor
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._

class BasicAuthToken(maybeValue: Option[String])
case class CustomsNotificationConfig(maybeBasicAuthToken: Option[String],
                                     notificationQueueConfig: NotificationQueueConfig,
                                     notificationConfig: NotificationConfig,
                                     notificationMetricsConfig: NotificationMetricsConfig,
                                     unblockPollerConfig: UnblockPollerConfig)
case class NotificationQueueConfig(url: String)
case class NotificationConfig(internalClientIds: Seq[String],
                              ttlInSeconds: Int,
                              retryPollerEnabled: Boolean,
                              retryPollerInterval: FiniteDuration,
                              retryPollerAfterFailureInterval: FiniteDuration,
                              retryPollerInProgressRetryAfter: FiniteDuration,
                              retryPollerInstances: Int,
                              nonBlockingRetryAfterMinutes: Int,
                              hotFixTranslates: Seq[String] = Seq("old:new"))
case class NotificationMetricsConfig(baseUrl: String)
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
class AppConfig @Inject()(configValidatedNel: ConfigValidatedNelAdaptor, logger: CdsLogger){
//This class/file is fairly ridiculous but as it is config I have left the bizarre Nel stuff alone
  private val config: CustomsNotificationConfig = {
    val root = configValidatedNel.root

     (configValidatedNel.root.maybeString("auth.token.internal"),
      configValidatedNel.service("notification-queue").serviceUrl.map(NotificationQueueConfig.apply), (
        root.stringSeq("internal.clientIds"),
        root.int("ttlInSeconds"),
        root.boolean("retry.poller.enabled"),
        root.int("retry.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS)),
        root.int("retry.poller.retryAfterFailureInterval.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS)),
        root.int("retry.poller.inProgressRetryAfter.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS)),
        root.int("retry.poller.instances"),
        root.int("non.blocking.retry.after.minutes"),
        root.stringSeq("hotfix.translates")
        ).mapN(NotificationConfig),
      configValidatedNel.service("customs-notification-metrics").serviceUrl.map(NotificationMetricsConfig.apply),
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
//TODO delete some of these and see if it breaks
  val maybeBasicAuthToken: Option[String] = config.maybeBasicAuthToken
  val notificationQueueConfig: NotificationQueueConfig = config.notificationQueueConfig
  val notificationConfig: NotificationConfig = config.notificationConfig
  val notificationMetricsConfig: NotificationMetricsConfig = config.notificationMetricsConfig
  val unblockPollerConfig: UnblockPollerConfig = config.unblockPollerConfig
}
