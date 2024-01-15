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

package uk.gov.hmrc.customs.notification.util

import org.bson.types.ObjectId as BsonObjectId
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.{ServerProvider, WsScalaTestClient}
import play.api.libs.ws.{WSClient, WSResponse}
import uk.gov.hmrc.customs.notification.models
import uk.gov.hmrc.customs.notification.util.IntegrationTestHelpers.{Endpoints, validControllerRequestHeaders}
import uk.gov.hmrc.customs.notification.util.TestData.*


trait WsClientHelpers extends WsScalaTestClient
  with ScalaFutures
  with IntegrationPatience {
  self: ServerProvider =>

  protected implicit lazy val wsClient: WSClient = app.injector.instanceOf[WSClient]

  protected def mockObjectIdService: MockObjectIdService

  protected def makeValidNotifyRequestAdding(newHeader: (String, String)): WSResponse =
    wsUrl(Endpoints.Notify)
      .withHttpHeaders((validControllerRequestHeaders(UntranslatedCsid) + newHeader).toList *)
      .post(ValidXml)
      .futureValue

  protected def makeValidNotifyRequestWithout(headerName: String): WSResponse =
    wsUrl(Endpoints.Notify)
      .withHttpHeaders(validControllerRequestHeaders(UntranslatedCsid).removed(headerName).toList *)
      .post(ValidXml)
      .futureValue

  protected def makeValidNotifyRequest(csid: models.ClientSubscriptionId = UntranslatedCsid,
                                       toAssign: BsonObjectId = ObjectId): WSResponse = {
    mockObjectIdService.nextTimeGive(toAssign)

    wsUrl(Endpoints.Notify)
      .withHttpHeaders(validControllerRequestHeaders(csid).toList *)
      .post(ValidXml)
      .futureValue
  }

}
