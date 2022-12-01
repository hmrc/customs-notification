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

import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.NotificationConfig

/**
 * Hot fix for
 * https://jira.tools.tax.service.gov.uk/browse/DCWL-851
 */
class FieldsIdMapperHotFix(logger: CdsLogger, notificationConfig: NotificationConfig) {

  val fieldIds = Map(notificationConfig.hotFixOld -> notificationConfig.hotFixNew)

  def translate(fieldsId: String): String = {
    val safeFieldsID = fieldIds.getOrElse(fieldsId, fieldsId)
    if (safeFieldsID != fieldsId) {
      logger.warn(s"FieldsIdMapperHotFix: translating fieldsId [$fieldsId] to [$safeFieldsID].")
    }
    safeFieldsID
  }
}

