/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.controllers

import uk.gov.hmrc.customs.notification.logging.NotificationLogger

/**
 * Hot fix for
 * https://jira.tools.tax.service.gov.uk/browse/DCWL-851
 */
class FieldsIdMapperHotFix(logger: NotificationLogger)(implicit md: RequestMetaData) {

  private val fieldIds = Map(
    "c86521a1-3bc3-4408-8ba4-4f51acaeb4d9" -> FieldsIdMapperHotFix.workingFieldsId,
    "f964448d-7cf0-444e-9027-172162235dbf" -> FieldsIdMapperHotFix.workingFieldsId,
    "8a2e1a95-6240-4256-9439-2ee0c59a16d6" -> FieldsIdMapperHotFix.workingFieldsId,
  )

  def translate(fieldsId: String): String = {
    val safeFieldsID = fieldIds.getOrElse(fieldsId, fieldsId)
    if (safeFieldsID != fieldsId) {
      logger.warn(s"FieldsIdMapperHotFix: translating fieldsId [$fieldsId] to [$safeFieldsID].")
    }
    safeFieldsID
  }
}

object FieldsIdMapperHotFix {
  val workingFieldsId = "0d6d358c-03ec-4e01-a6b1-11d05479c361"
}