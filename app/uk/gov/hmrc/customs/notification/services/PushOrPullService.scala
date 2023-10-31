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

package uk.gov.hmrc.customs.notification.services

import org.bson.types.ObjectId
import play.api.http.Status.OK
import play.api.libs.json.Json
import uk.gov.hmrc.customs.notification.config.AppConfig

import javax.inject.Inject
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PushConnector}
import uk.gov.hmrc.customs.notification.models.repo.{FailedButNotBlocked, NotificationWorkItem}
import uk.gov.hmrc.customs.notification.models.requests.PushNotificationRequest
import uk.gov.hmrc.customs.notification.models.{ApiSubscriptionFields, ClientNotification}
import uk.gov.hmrc.customs.notification.util.HeaderNames.NOTIFICATION_ID_HEADER_NAME
import uk.gov.hmrc.customs.notification.util.{DateTimeHelper, NotificationLogger, NotificationWorkItemRepo}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Succeeded
import uk.gov.hmrc.mongo.workitem.WorkItem

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PushOrPullService @Inject()(apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                                  notificationQueueConnector: NotificationQueueConnector,
                                  logger: NotificationLogger,
                                  config: AppConfig,
                                  pushConnector: PushConnector,
                                  auditingService: AuditingService,
                                  repo: NotificationWorkItemRepo)(implicit ec: ExecutionContext){

  def pushOrPull(workItem: WorkItem[NotificationWorkItem], apiSubscriptionFields: ApiSubscriptionFields, shouldUpdateFailureCount: Boolean)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val notificationWorkItem: NotificationWorkItem = workItem.item
    implicit val loggingContext = notificationWorkItem
    implicit val maybeUpdatedHeaderCarrier: HeaderCarrier = notificationWorkItem.notification.notificationId.fold(HeaderCarrier())(notificationId =>
      HeaderCarrier().withExtraHeaders(Seq((NOTIFICATION_ID_HEADER_NAME, notificationId.toString)): _*))
    logger.debug(s"attempting to push or pull $workItem")
    if (apiSubscriptionFields.isPush) {
      val pushNotificationRequest: PushNotificationRequest =
        PushNotificationRequest.buildPushNotificationRequest(
          declarantCallbackData = apiSubscriptionFields.fields,
          notification = notificationWorkItem.notification,
          clientSubscriptionId = notificationWorkItem.clientSubscriptionId)
      sendPushNotificationRequest(notificationWorkItem, workItem.id, pushNotificationRequest, shouldUpdateFailureCount)(maybeUpdatedHeaderCarrier)
    } else { //If is a pull request
      val notUsedBsonId: String = "123456789012345678901234"
      val clientNotification: ClientNotification = ClientNotification(
        csid = notificationWorkItem._id,
        notification = notificationWorkItem.notification,
        timeReceived = None,
        metricsStartDateTime = None,
        id = new ObjectId(notUsedBsonId))
      sendPullNotificationRequest(notificationWorkItem, workItem.id, clientNotification, shouldUpdateFailureCount)(hc = maybeUpdatedHeaderCarrier)
    }
  }
//TODO move this
  def getApiSubscriptionFields(notificationWorkItem: NotificationWorkItem, workItemId: ObjectId, headerCarrier: HeaderCarrier): Future[Option[ApiSubscriptionFields]] = {
    val getApiSubscriptionFieldsResponse: Future[HttpResponse] = apiSubscriptionFieldsConnector.getApiSubscriptionFields(notificationWorkItem.clientSubscriptionId, headerCarrier)
    getApiSubscriptionFieldsResponse.map { httpResponse =>
      httpResponse.status match {
        case OK =>
          val parsedResponse: ApiSubscriptionFields = Json.parse(httpResponse.body).as[ApiSubscriptionFields]
          logger.debug(s"api-subscription-fields service parsed response=$parsedResponse")(notificationWorkItem)
          Some(parsedResponse)
        case notOkStatus =>
          updateRepoForFailedResponse(notificationWorkItem, workItemId, "GetApiSubscriptionFields", notOkStatus, false)
          None
      }
    }
  }

  private def sendPushNotificationRequest(notificationWorkItem: NotificationWorkItem, workItemId: ObjectId, pushNotificationRequest: PushNotificationRequest, shouldUpdateFailureCount: Boolean)(implicit  hc: HeaderCarrier): Future[Boolean] = {
    val isInternal: Boolean = config.notificationConfig.internalClientIds.contains(notificationWorkItem.clientId.toString)
    val internalOrExternal: String = if(isInternal) "internal" else "external"
    val eventualResponse: Future[HttpResponse] =
      if (isInternal) {
        pushConnector.postInternalPush(pushNotificationRequest)
      } else {
        pushConnector.postExternalPush(pushNotificationRequest)
      }

    eventualResponse.map { response =>
      response.status match {
        case OK =>
          logger.info(s"$internalOrExternal push succeeded for $notificationWorkItem")(notificationWorkItem)
          repo.setCompletedStatus(workItemId, Succeeded)
          true
        case notOkStatus =>
          updateRepoForFailedResponse(notificationWorkItem, workItemId, s"$internalOrExternal push", notOkStatus, shouldUpdateFailureCount)
          false
      }
    }
  }

  private def sendPullNotificationRequest(notificationWorkItem: NotificationWorkItem, workItemId: ObjectId, clientNotification: ClientNotification, shouldUpdateFailureCount: Boolean)(implicit hc: HeaderCarrier): Future[Boolean] = {
    notificationQueueConnector.postToQueue(clientNotification).map { response =>
      response.status match {
        case OK =>
          logger.info(s"pull succeeded for $notificationWorkItem")(notificationWorkItem)
          repo.setCompletedStatus(workItemId, Succeeded)
          true
        case notOkStatus =>
          updateRepoForFailedResponse(notificationWorkItem, workItemId, "pull", notOkStatus, shouldUpdateFailureCount)
          false
      }
    }
  }

  private def updateRepoForFailedResponse(notificationWorkItem: NotificationWorkItem, workItemId: ObjectId, nameOfServiceThatFailed: String, responseStatus: Int, shouldUpdateFailureCount: Boolean): Future[Unit] = {
    if (shouldUpdateFailureCount) repo.incrementFailureCount(workItemId)

    responseStatus match {
      case status if status >= 300 && status < 500 =>
        retryFailedWith300or400(notificationWorkItem, workItemId, nameOfServiceThatFailed, status)
      case status if (status > 200 && status < 300) || (status >= 500) =>
        retryFailedWith500or2xxNotOk(notificationWorkItem, workItemId, nameOfServiceThatFailed, status)
    }
  }

  private def retryFailedWith300or400(notificationWorkItem: NotificationWorkItem, workItemId: ObjectId, nameOfServiceThatFailed: String, responseStatus: Int): Future[Unit] = {
    logger.info(s"$nameOfServiceThatFailed failed for $notificationWorkItem with error $responseStatus. Setting status to " +
      s"PermanentlyFailed for all notifications with clientSubscriptionId ${notificationWorkItem.clientSubscriptionId.toString}")(notificationWorkItem)
    val availableAt = DateTimeHelper.zonedDateTimeUtc.plusMinutes(config.notificationConfig.nonBlockingRetryAfterMinutes)
    logger.error(s"Status response $responseStatus received while pushing notification, setting availableAt to $availableAt")(notificationWorkItem)
    repo.setCompletedStatusWithAvailableAt(workItemId, FailedButNotBlocked.convertToHmrcProcessingStatus, responseStatus, availableAt) // increase failure count
  }

  //TODO should we be treating not 200 as 500???
  private def retryFailedWith500or2xxNotOk(notificationWorkItem: NotificationWorkItem, workItemId: ObjectId, nameOfServiceThatFailed: String, responseStatus: Int): Future[Unit] = {
    logger.info(s"$nameOfServiceThatFailed failed for $notificationWorkItem with error $responseStatus. Setting status to " +
      s"FailedAndBlocked for all notifications with clientSubscriptionId ${notificationWorkItem.clientSubscriptionId.toString}")(notificationWorkItem)
    repo.setFailedAndBlocked(workItemId, responseStatus)
  }
}
