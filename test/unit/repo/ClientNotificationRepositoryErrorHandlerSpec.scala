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

package unit.repo

import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsObject, Json}
import reactivemongo.api.commands.{DefaultWriteResult, WriteConcernError, WriteError}
import reactivemongo.play.json.commands.JSONFindAndModifyCommand.{FindAndModifyResult, UpdateLastError}
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepositoryErrorHandler
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubCdsLogger
import util.TestData.client1Notification1

class ClientNotificationRepositoryErrorHandlerSpec extends UnitSpec with MockitoSugar {

  private val stubCdsLogger = StubCdsLogger()
  private val errorHandler = new ClientNotificationRepositoryErrorHandler(stubCdsLogger)

  "ClientNotificationRepositoryErrorHandler" can {

    "handle update" should {

      "return true if there are no database errors and at least one record inserted" in {
        val lastError = UpdateLastError(updatedExisting = true, upsertedId = Some(1), n = 1, err = None)

        val successfulUpdateResult = findAndModifyResult(lastError)

        errorHandler.handleUpdateError(successfulUpdateResult, "ERROR_MSG", client1Notification1) shouldBe true
      }

      "throw a RuntimeException if there are no database errors but no record inserted" in {
        val lastError = UpdateLastError(updatedExisting = false, upsertedId = None, n = 0, err = None)
        val noRecordsUpdateResult = findAndModifyResult(lastError)

        val caught = intercept[RuntimeException](errorHandler.handleUpdateError(noRecordsUpdateResult, "ERROR_MSG", client1Notification1))

        caught.getMessage shouldBe "ERROR_MSG"
      }

      "throw a RuntimeException if there is a database error" in {
        val lastError = UpdateLastError(updatedExisting = false, upsertedId = None, n = 0, err = Some("database error"))
        val errorUpdateResult = findAndModifyResult(lastError)

        val caught = intercept[RuntimeException](errorHandler.handleUpdateError(errorUpdateResult, "ERROR_MSG", client1Notification1))

        caught.getMessage shouldBe "ERROR_MSG. database error"
      }
    }

    "handle Delete" should {
      "return true if there are no database errors and at least one record deleted" in {
        val successfulWriteResult = writeResult(alteredRecords = 1)

        errorHandler.handleDeleteError(successfulWriteResult, "ERROR_MSG") shouldBe true
      }

      "return false if there are no database errors and no record deleted" in {
        val noDeletedRecordsWriteResult = writeResult(alteredRecords = 0)

        errorHandler.handleDeleteError(noDeletedRecordsWriteResult, "ERROR_MSG") shouldBe false
      }

      "throw a RuntimeException if there is a database error" in {
        val writeConcernError = Some(WriteConcernError(1, "ERROR"))
        val errorWriteResult = writeResult(alteredRecords = 0, writeConcernError = writeConcernError)

        val caught = intercept[RuntimeException](errorHandler.handleDeleteError(errorWriteResult, "ERROR_MSG"))

        caught.getMessage shouldBe "ERROR_MSG. WriteConcernError(1,ERROR)"
      }
    }
  }

  private def findAndModifyResult(lastError: UpdateLastError): FindAndModifyResult = {
    FindAndModifyResult(Some(lastError), Json.toJson(client1Notification1).asOpt[JsObject])
  }

  private def writeResult(alteredRecords: Int, writeErrors: Seq[WriteError] = Nil,
                          writeConcernError: Option[WriteConcernError] = None) = {
    DefaultWriteResult(
      ok = true,
      n = alteredRecords,
      writeErrors = writeErrors,
      writeConcernError = writeConcernError,
      code = None,
      errmsg = None)
  }

}
