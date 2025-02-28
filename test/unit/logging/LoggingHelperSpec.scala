/*
 * Copyright 2024 HM Revenue & Customs
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

package unit.logging

import uk.gov.hmrc.customs.notification.logging.LoggingHelper
import util.RequestHeaders.LoggingHeadersWithAuth
import util.TestData._
import util.UnitSpec

class LoggingHelperSpec extends UnitSpec {

  "LoggingHelper" should {

    "logMsgPrefix with both clientSubscriptionId and conversationId" in {
      val actual = LoggingHelper.logMsgPrefix(clientSubscriptionId, conversationId)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientSubscriptionId=ffff01f9-ec3b-4ede-b263-61b626dde232]"
    }

    "logMsgPrefix with clientNotification" in {
      val actual = LoggingHelper.logMsgPrefix(client1Notification1)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientSubscriptionId=eaca01f9-ec3b-4ede-b263-61b626dde232]"
    }

    "format" in {
      val actual = LoggingHelper.format(errorMsg, requestMetaData)

      actual shouldBe s"[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] $errorMsg"
    }

    "format Debug with URL" in {
      val actual = LoggingHelper.formatDebug(errorMsg, Some(url))(requestMetaData)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] ERROR url=http://some-url"
    }

    "format Debug with URL and Payload" in {
      val actual = LoggingHelper.formatDebug(errorMsg, Some(url), Some("PAYLOAD"))(requestMetaData)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] ERROR url=http://some-url\npayload=\nPAYLOAD"
    }

    "format DEBUG with url and payload" in {
      val actual = LoggingHelper.formatDebug(debugMsg, Some(url), Some(ValidXML.toString()))(requestMetaData)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] DEBUG url=http://some-url\npayload=\n<Foo>Bar</Foo>"
    }

    "format with headers" in {
      val actual = LoggingHelper.formatWithHeaders(debugMsg, LoggingHeadersWithAuth)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232][clientId=ClientId] DEBUG\nheaders=List((X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Client-ID,ClientId), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde231), (Authorization,Basic YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=))"
    }

    "format without headers" in {
      val actual = LoggingHelper.formatWithoutHeaders(debugMsg, LoggingHeadersWithAuth)

      actual shouldBe "[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232][clientId=ClientId] DEBUG"
    }

  }
}
