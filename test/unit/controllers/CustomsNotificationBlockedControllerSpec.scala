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

package unit.controllers

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import play.api.mvc._
import play.api.test.Helpers._
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorInternalServerError, errorBadRequest}
import uk.gov.hmrc.customs.notification.controllers.CustomsNotificationBlockedController
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.model.SeqOfHeader
import uk.gov.hmrc.customs.notification.services.CustomsNotificationBlockedService
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationBlockedControllerSpec
  extends UnitSpec
    with MockitoSugar
    with BeforeAndAfterEach {

  private val mockService = mock[CustomsNotificationBlockedService]
  private val mockLogger = mock[NotificationLogger]
  private val controller = new CustomsNotificationBlockedController(mockLogger, mockService)

  override protected def beforeEach() {
    reset(mockService, mockLogger)
  }

  "CustomsNotificationBlockedController" should {
    "respond with status 200 for valid request" in {
      when(mockService.blockedCount(clientId1)).thenReturn(Future.successful(2))

      testSubmitResult(ValidBlockedCountRequest) { result =>
        status(result) shouldBe OK
        contentAsString(result) shouldBe "<?xml version='1.0' encoding='UTF-8'?>\n<pushNotificationBlockedCount>2</pushNotificationBlockedCount>"
        contentType(result) shouldBe Some(MimeTypes.XML)
      }
      verifyLog("info", "blocked count of 2 returned")
    }

    "respond with status 400 for missing client id header" in {
      when(mockService.blockedCount(clientId1)).thenReturn(Future.successful(2))

      testSubmitResult(InvalidBlockedCountRequest) { result =>
        status(result) shouldBe 400
        await(result) shouldBe errorBadRequest("X-Client-ID required").XmlResult
      }
      verifyLogWithHeaders("errorWithHeaders", "missing X-Client-ID header")
    }

    "respond with 500 when unexpected failure happens" in {
      when(mockService.blockedCount(clientId1)).thenReturn(Future.failed(emulatedServiceFailure))

      testSubmitResult(ValidBlockedCountRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
        await(result) shouldBe ErrorInternalServerError.XmlResult
      }
      verifyLog("error", s"unable to get blocked count due to Emulated service failure.")
    }

  }

  private def testSubmitResult(request: Request[AnyContent])(test: Future[Result] => Unit) {
    val result = controller.blockedCount().apply(request)
    test(result)
  }

  private def verifyLog(method: String, message: String): Unit = {
    PassByNameVerifier(mockLogger, method)
      .withByNameParam(message)
      .withParamMatcher(any[HasId])
      .verify()
  }

  private def verifyLogWithHeaders(method: String, message: String): Unit = {
    PassByNameVerifier(mockLogger, method)
      .withByNameParam(message)
      .withByNameParamMatcher(any[SeqOfHeader])
      .verify()
  }

}
