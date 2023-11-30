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

import uk.gov.hmrc.customs.notification.config.CsidTranslationHotfixConfig
import uk.gov.hmrc.customs.notification.models.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.util.Logger

import javax.inject.{Inject, Singleton}


@Singleton
class CsidTranslationHotfixService @Inject()(logger: Logger,
                                             config: CsidTranslationHotfixConfig) {

  def translate(csid: ClientSubscriptionId): ClientSubscriptionId = {
    val safeCsid = config.newByOldCsids.getOrElse(csid, csid)
    if (safeCsid != csid) {
      logger.warn(s"FieldsIdMapperHotFix: translating fieldsId (aka client subscription ID) [$csid] to [$safeCsid].")
    }
    safeCsid
  }
}
