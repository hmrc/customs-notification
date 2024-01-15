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
import cats.implicits.*
import play.api.ConfigLoader.*
import play.api.{ConfigLoader, Configuration, Logging}
import uk.gov.hmrc.customs.notification.config.ConfigValidator.{get, *}
import uk.gov.hmrc.customs.notification.models.{ClientId, ClientSubscriptionId}
import uk.gov.hmrc.http.Authorization

import java.net.URL
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.*

@Singleton
class BlockedCsidDbMigrationConfig @Inject()(c: ConfigValidator) {
  val enabled: Boolean = c.enableBlockedCsidDbMigration
}

@Singleton
class AppConfig @Inject()(c: ConfigValidator) {
  val name: String = c.name
}

@Singleton
class BasicAuthConfig @Inject()(c: ConfigValidator) {
  val token: Authorization = c.basicAuthToken
}

@Singleton
class SendConfig @Inject()(c: ConfigValidator) {
  val internalClientIds: Set[ClientId] = c.internalClientIds
  val externalPushUrl: URL = c.externalPushUrl
  val pullQueueUrl: URL = c.pullQueueUrl
}

@Singleton
class ClientDataConfig @Inject()(c: ConfigValidator) {
  val url: URL = c.apiSubscriptionFieldsUrl
}

@Singleton
class ClientDataCacheConfig @Inject()(c: ConfigValidator) {
  val ttl: FiniteDuration = c.clientDataCacheTtl
}

@Singleton
class MetricsConfig @Inject()(c: ConfigValidator) {
  val url: URL = c.metricsUrl
  val retryCounterName: String = c.retryMetricCounterName
}

@Singleton
class RetryDelayConfig @Inject()(c: ConfigValidator) {
  val failedButNotBlocked: FiniteDuration = c.failedButNotBlockedAvailableAfter
  val failedAndBlocked: FiniteDuration = c.failedAndBlockedAvailableAfter
}

@Singleton
class RetrySchedulerConfig @Inject()(c: ConfigValidator) {
  val enabled: Boolean = c.enableRetryScheduler
  val failedAndBlockedDelay: FiniteDuration = c.retryFailedAndBlockedDelay
  val failedButNotBlockedDelay: FiniteDuration = c.retryFailedButNotBlockedDelay
}

@Singleton
class RepoConfig @Inject()(c: ConfigValidator) {
  val notificationTtl: FiniteDuration = c.notificationTtl
  val inProgressRetryDelay: FiniteDuration = c.retryFailedButNotBlockedDelay
  val maxTransactionRetries: Int = c.maxTransactionRetries
}

@Singleton
class CsidTranslationHotfixConfig @Inject()(c: ConfigValidator) {
  val newByOldCsids: Map[ClientSubscriptionId, ClientSubscriptionId] = c.newByOldCsids
}

@Singleton
class ConfigValidator @Inject()(implicit config: Configuration) extends Logging {

  val (
    name: String,
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
    failedButNotBlockedAvailableAfter: FiniteDuration,
    failedAndBlockedAvailableAfter: FiniteDuration,
    maxTransactionRetries: Int,
    retryMetricCounterName: String,
    newByOldCsids: Map[ClientSubscriptionId, ClientSubscriptionId],
    enableBlockedCsidDbMigration: Boolean,
    clientDataCacheTtl: FiniteDuration
    ) = {

    (get[String]("appName"),
      get[Authorization]("auth-token"),
      get[Set[ClientId]]("internal-client-ids"),
      getServiceUrl("public-notification"),
      getServiceUrl("notification-queue"),
      getServiceUrl("api-subscription-fields"),
      getServiceUrl("customs-notification-metrics"),
      get[FiniteDuration]("notification-ttl"),
      get[Boolean]("retry.scheduler.enabled"),
      get[FiniteDuration]("retry.delay.failed-and-blocked"),
      get[FiniteDuration]("retry.delay.failed-but-not-blocked"),
      get[FiniteDuration]("retry.available-after.failed-but-not-blocked"),
      get[FiniteDuration]("retry.available-after.failed-and-blocked"),
      get[Int]("retry.max-transaction-retries"),
      get[String]("retry.metric-name"),
      get[Map[ClientSubscriptionId, ClientSubscriptionId]]("hotfix.translates"),
      get[Boolean]("blocked-csid-migration-enabled"),
      get[FiniteDuration]("client-data-cache-ttl")
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

  private def getServiceUrl(name: String)(implicit config: Configuration): ValidatedNel[String, URL] = {
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
    mapLoader[String]
      .map(_
        .map { case (k, v) =>
          ClientSubscriptionId(UUID.fromString(k)) -> ClientSubscriptionId(UUID.fromString(v))
        }
      )
}