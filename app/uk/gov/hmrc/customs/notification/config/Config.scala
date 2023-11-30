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

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Validated, ValidatedNel}
import cats.implicits._
import play.api.ConfigLoader._
import play.api.{ConfigLoader, Configuration}
import uk.gov.hmrc.customs.notification.config.ConfigValidator._
import uk.gov.hmrc.customs.notification.models.{ClientId, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.util.Logger
import uk.gov.hmrc.http.Authorization

import java.net.URL
import java.util.UUID
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
class BasicAuthConfig @Inject()(c: ConfigValidator) {
  val token = c.basicAuthToken
}

@Singleton
class SendConfig @Inject()(c: ConfigValidator) {
  val internalClientIds: Set[ClientId] = c.internalClientIds
  val externalPushUrl: URL = c.externalPushUrl
  val pullQueueUrl: URL = c.pullQueueUrl
  val failedAndNotBlockedAvailableAfter: FiniteDuration = c.failedAndNotBlockedAvailableAfter
}

@Singleton
class ApiSubscriptionFieldsConfig @Inject()(c: ConfigValidator) {
  val url: URL = c.apiSubscriptionFieldsUrl
}

@Singleton
class MetricsConfig @Inject()(c: ConfigValidator) {
  val url: URL = c.metricsUrl
  val retryCounterName: String = c.retryMetricCounterName
}

@Singleton
class RetryConfig @Inject()(c: ConfigValidator) {
  val enabled: Boolean = c.enableRetryScheduler
  val failedAndBlockedDelay: FiniteDuration = c.retryFailedAndBlockedDelay
  val failedButNotBlockedDelay: FiniteDuration = c.retryFailedButNotBlockedDelay
}

@Singleton
class RepoConfig @Inject()(c: ConfigValidator) {
  val notificationTtl: FiniteDuration = c.notificationTtl

  /**
   * We should only have to retry 'inProgress' notifications if something exceptional happens during processing
   * We'll retry as if it were 'FailedButNotBlocked'
   */
  val inProgressRetryDelay: FiniteDuration = c.retryFailedButNotBlockedDelay
}

@Singleton
class CsidTranslationHotfixConfig @Inject()(c: ConfigValidator) {
  val newByOldCsids: Map[ClientSubscriptionId, ClientSubscriptionId] = c.newByOldCsids
}


@Singleton
class ConfigValidator @Inject()(implicit config: Configuration) {
  private lazy val logger = play.api.Logger("customs-notification")

  val (
    basicAuthToken: Authorization,
    internalClientIds: Set[ClientId],
    externalPushUrl: URL,
    pullQueueUrl: URL,
    apiSubscriptionFieldsUrl: URL,
    metricsUrl: URL,
    notificationTtl: FiniteDuration,
    enableRetryScheduler: Boolean,
    retryFailedAndBlockedDelay: FiniteDuration,
    retryFailedButNotBlockedDelay: FiniteDuration,
    failedAndNotBlockedAvailableAfter: FiniteDuration,
    retryMetricCounterName: String,
    newByOldCsids: Map[ClientSubscriptionId, ClientSubscriptionId],
    ) = {

    (get[Authorization]("auth.token.internal"),
      get[Set[ClientId]]("internal.clientIds"),
      getServiceUrl("public-notification"),
      getServiceUrl("notification-queue"),
      getServiceUrl("api-subscription-fields"),
      getServiceUrl("customs-notification-metrics"),
      get[FiniteDuration]("notification-ttl"),
      get[Boolean]("retry.scheduler.enabled"),
      get[FiniteDuration]("retry.failed-and-blocked.delay"),
      get[FiniteDuration]("retry.failed-but-not-blocked.delay"),
      get[FiniteDuration]("retry.failed-but-not-blocked.available-after"),
      get[String]("retry.metric-name"),
      get[Map[ClientSubscriptionId, ClientSubscriptionId]]("hotfix.translates")
    ).tupled match {
      case Valid(c) => c
      case Invalid(errors) =>
        val errorMsg = errors.toList.mkString("\n")
        logger.error(errorMsg)
        throw new IllegalStateException(errorMsg)
    }
  }
}

object ConfigValidator {

  private def formatErrorMessage(errorMessage: String, path: String): String = {
    s"Error loading [$path] from config: $errorMessage"
  }

  def get[A](path: String)(implicit l: ConfigLoader[A], config: Configuration): ValidatedNel[String, A] = {
    Validated.catchNonFatal {
      config.get[A](path)
    }.leftMap(e => formatErrorMessage(e.getMessage, path))
      .toValidatedNel
  }

  def getServiceUrl(name: String)(implicit config: Configuration): ValidatedNel[String, URL] = {
    val rootPathPrefix = "microservice.services."
    val servicePathPrefix = s"$rootPathPrefix$name."

    val protocol =
      get[String](servicePathPrefix + "protocol")
        .orElse(get[String](rootPathPrefix + "protocol"))
        .getOrElse("http")
    val host = get[String](servicePathPrefix + "host")
    val port = get[String](servicePathPrefix + "port")
    val context = get[String](servicePathPrefix + "context")

    (host, port, context)
      .mapN { case (host, port, context) => s"$protocol://$host:$port$context" }
      .andThen { s =>
        Validated.catchNonFatal(new URL(s)).leftMap(_.getMessage).toValidatedNel
      }.leftMap { errors =>
      val errorMessage = errors.toList.mkString("\n")
      formatErrorMessage(errorMessage, servicePathPrefix.dropRight(1))
    }.toValidatedNel
  }

  implicit val clientIdsLoader: ConfigLoader[Set[ClientId]] = seqStringLoader.map(_.toSet.map(ClientId(_)))
  implicit val authLoader: ConfigLoader[Authorization] = stringLoader.map(Authorization)
  implicit val csidTranslationsLoader: ConfigLoader[Map[ClientSubscriptionId, ClientSubscriptionId]] =
    mapLoader[String].map(_.map { case (k, v) => ClientSubscriptionId(UUID.fromString(k)) -> ClientSubscriptionId(UUID.fromString(v)) })
}