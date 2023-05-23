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

package unit.controllers

import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc._
import play.api.test.Helpers
import play.api.test.Helpers._
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{ErrorInternalServerError, ErrorNotFound, errorBadRequest}
import uk.gov.hmrc.customs.notification.controllers.CustomsNotificationBlockedController
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.CustomsNotificationBlockedService
import util.UnitSpec
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationBlockedControllerSpec
  extends UnitSpec
    with MockitoSugar
    with BeforeAndAfterEach
    with ControllerSpecHelper {

  private implicit val ec = Helpers.stubControllerComponents().executionContext
  private val mockService = mock[CustomsNotificationBlockedService]
  private val mockLogger = mock[NotificationLogger]
  private val controller = new CustomsNotificationBlockedController(mockService, Helpers.stubControllerComponents(), mockLogger)

  override protected def beforeEach(): Unit = {
    reset[Any](mockService, mockLogger)
  }

  "CustomsNotificationBlockedController" should {
    "when blocked-count endpoint is called" should {
      "respond with status 200 for valid request" in {
        when(mockService.blockedCount(clientId1)).thenReturn(Future.successful(2))

        testSubmitResult(ValidBlockedCountRequest, controller.blockedCount()) { result =>
          status(result) shouldBe OK
          contentAsString(result) shouldBe "<?xml version='1.0' encoding='UTF-8'?>\n<pushNotificationBlockedCount>2</pushNotificationBlockedCount>"
          contentType(result) shouldBe Some(MimeTypes.XML)
        }
        verifyLog("info", "blocked count of 2 returned", mockLogger)
      }

      "respond with status 400 for missing client id header" in {
        when(mockService.blockedCount(clientId1)).thenReturn(Future.successful(2))

        testSubmitResult(InvalidBlockedCountRequest, controller.blockedCount()) { result =>
          status(result) shouldBe 400
          await(result) shouldBe errorBadRequest("X-Client-ID required").XmlResult
        }
        verifyLogWithHeaders("errorWithHeaders", "missing X-Client-ID header when calling blocked-count endpoint", mockLogger)
      }

      "respond with 500 when unexpected failure happens" in {
        when(mockService.blockedCount(clientId1)).thenReturn(Future.failed(emulatedServiceFailure))

        testSubmitResult(ValidBlockedCountRequest, controller.blockedCount()) { result =>
          status(result) shouldBe INTERNAL_SERVER_ERROR
          await(result) shouldBe ErrorInternalServerError.XmlResult
        }
        verifyLog("error", "unable to get blocked count due to java.lang.UnsupportedOperationException: Emulated service failure.", mockLogger)
      }
    }

    "when blocked-flag endpoint is called" should {
      "respond with status 204 when notifications are unblocked" in {
        when(mockService.deleteBlocked(clientId1)).thenReturn(Future.successful(true))

        testSubmitResult(ValidDeleteBlockedRequest, controller.deleteBlocked()) { result =>
          status(result) shouldBe NO_CONTENT
        }
        verifyLog("info", "blocked flags deleted for clientId ClientId", mockLogger)
      }

      "respond with status 404 when no notifications are unblocked" in {
        when(mockService.deleteBlocked(clientId1)).thenReturn(Future.successful(false))

        testSubmitResult(ValidDeleteBlockedRequest, controller.deleteBlocked()) { result =>
          status(result) shouldBe NOT_FOUND
          await(result) shouldBe ErrorNotFound.XmlResult
        }
        verifyLog("info", "no blocked flags deleted for clientId ClientId", mockLogger)
      }

      "respond with status 400 for missing client id header" in {
        when(mockService.deleteBlocked(clientId1)).thenReturn(Future.successful(true))

        testSubmitResult(InvalidDeleteBlockedRequest, controller.deleteBlocked()) { result =>
          status(result) shouldBe 400
          await(result) shouldBe errorBadRequest("X-Client-ID required").XmlResult
        }
        verifyLogWithHeaders("errorWithHeaders", "missing X-Client-ID header when calling delete blocked-flag endpoint", mockLogger)
      }

      "respond with 500 when unexpected failure happens" in {
        when(mockService.deleteBlocked(clientId1)).thenReturn(Future.failed(emulatedServiceFailure))

        testSubmitResult(ValidDeleteBlockedRequest, controller.deleteBlocked()) { result =>
          status(result) shouldBe INTERNAL_SERVER_ERROR
          await(result) shouldBe ErrorInternalServerError.XmlResult
        }
        verifyLog("error", s"unable to delete blocked flags due to java.lang.UnsupportedOperationException: Emulated service failure.", mockLogger)
      }
    }
}

  private def testSubmitResult(request: Request[AnyContent], action: Action[AnyContent])(test: Future[Result] => Unit): Unit = {
    val result = action.apply(request)
    test(result)
  }
}
