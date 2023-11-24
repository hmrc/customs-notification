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
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._
import com.google.inject.AbstractModule
import uk.gov.hmrc.customs.api.common.config.{ConfigValidatedNelAdaptor, CustomsValidatedNel}
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.config.AppConfig.{validateUrl, validateUuid}
import uk.gov.hmrc.customs.notification.models.{ClientId, ClientSubscriptionId}
import uk.gov.hmrc.http.Authorization

import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._

/**
 * Responsible for reading the HMRC style Play2 configuration file, error handling, and de-serialising config into
 * the Scala model.
 *
 * Note this class is bound as an EagerSingleton to bind at application startup time - any exceptions will STOP the
 * application. If startup completes without any exceptions being thrown then dependent classes can be sure that
 * config has been loaded correctly.
 *
 * @param c adaptor for config services that returns a `ValidatedNel`
 */
@Singleton
class AppConfig @Inject()(c: ConfigValidatedNelAdaptor,
                          logger: CdsLogger) {
  val (
    basicAuthToken: Authorization,
    internalClientIds: Set[ClientId],
    externalPushUrl: URL,
    pullQueueUrl: URL,
    apiSubscriptionFieldsUrl: URL,
    metricsUrl: URL,
    ttlInSeconds: Int,
    retryFailedAndBlockedDelay: FiniteDuration,
    retryFailedAndNotBlockedDelay: FiniteDuration,
    retryPollerAfterFailureInterval: FiniteDuration,
    failedAndNotBlockedAvailableAfterMinutes: Int,
    hotFixTranslates: Map[ClientSubscriptionId, ClientSubscriptionId]
    ) = {

    (c.root.string("auth.token.internal").map(Authorization),
      c.root.stringSeq("internal.clientIds").map(_.toSet.map(ClientId(_))),
      c.service("public-notification").serviceUrl andThen validateUrl,
      c.service("notification-queue").serviceUrl andThen validateUrl,
      c.service("api-subscription-fields").serviceUrl andThen validateUrl,
      c.service("customs-notification-metrics").serviceUrl andThen validateUrl,
      c.root.int("ttlInSeconds"),
      c.root.int("retry.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS)),
      c.root.int("unblock.poller.interval.milliseconds").map(millis => Duration(millis, TimeUnit.MILLISECONDS)),
      c.root.int("retry.poller.retryAfterFailureInterval.seconds").map(seconds => Duration(seconds, TimeUnit.SECONDS)),
      c.root.int("non.blocking.retry.after.minutes"),
      c.root.stringSeq("hotfix.translates").andThen(_.map { pair =>
        val mapping = pair.split(":").toList
        (validateUuid(mapping.head) -> validateUuid(mapping(1)))
          .mapN { case (before, after) =>
            ClientSubscriptionId(before) -> ClientSubscriptionId(after)
          }
      }.sequence.map(_.toMap))
    ).mapN { case (a, b, c, d, e, f, g, h, i, j, k, l) =>
      (a, b, c, d, e, f, g, h, i, j, k, l)
    } match {
      case Valid(c) => c
      case Invalid(errors) =>
        val errorMsg = errors.toList.mkString("\n")
        logger.error(errorMsg)
        throw new IllegalStateException(errorMsg)
    }
  }
}




object AppConfig {
  def validateUrl(urlString: String): CustomsValidatedNel[URL] = {
    Validated.catchNonFatal(new URL(urlString)).leftMap(_.getMessage).toValidatedNel
  }

  def validateUuid(uuidString: String): CustomsValidatedNel[UUID] = {
    Validated.catchNonFatal(UUID.fromString(uuidString)).leftMap(_.getMessage).toValidatedNel
  }
}
