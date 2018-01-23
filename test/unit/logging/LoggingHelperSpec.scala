/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.RequestHeaders.{LoggingHeaders, LoggingHeadersWithAuth}
import util.TestData._

class LoggingHelperSpec extends UnitSpec {

  private implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = LoggingHeaders)

  "LoggingHelper" should {

    "format ERROR" in {
      LoggingHelper.formatError(errorMsg) shouldBe s"[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $errorMsg"
    }

    "format ERROR with headers" in {
      LoggingHelper.formatError(errorMsg, LoggingHeaders) shouldBe s"[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $errorMsg"
    }

    "format INFO with HeaderCarrier" in {
      LoggingHelper.formatInfo(infoMsg) shouldBe s"[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $infoMsg"
    }

    "format INFO with headers" in {
      LoggingHelper.formatInfo(infoMsg, LoggingHeaders) shouldBe s"[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $infoMsg"
    }

    "format DEBUG with HeaderCarrier" in {
      val requestChain = hc.requestChain.value
      LoggingHelper.formatDebug(debugMsg) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg
           |headers=List((X-Request-Chain,$requestChain), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))""".stripMargin
    }

    "format DEBUG with headers" in {
      LoggingHelper.formatDebug(debugMsg, LoggingHeaders) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg
           |headers=List((X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))""".stripMargin
    }

    "format DEBUG with url and payload" in {
      val requestChain = hc.requestChain.value
      LoggingHelper.formatDebug(debugMsg, Some(url), Some(ValidXML.toString())) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg url=http://some-url
           |headers=List((X-Request-Chain,$requestChain), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))
           |payload=
           |<Foo>Bar</Foo>""".stripMargin
    }

    "format DEBUG with url, payload and auth header overwritten" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = LoggingHeadersWithAuth)

      val requestChain = hc.requestChain.value
      LoggingHelper.formatDebug(debugMsg, Some(url), Some(ValidXML.toString())) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg url=http://some-url
           |headers=List((X-Request-Chain,$requestChain), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232), (Authorization,Basic YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=))
           |payload=
           |<Foo>Bar</Foo>""".stripMargin
    }

    "format DEBUG with url and no payload" in {
      val requestChain = hc.requestChain.value
      LoggingHelper.formatDebug(debugMsg, Some(url)) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg url=http://some-url
           |headers=List((X-Request-Chain,$requestChain), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))""".stripMargin
    }

    "format DEBUG with payload and no url" in {
      val requestChain = hc.requestChain.value
      LoggingHelper.formatDebug(debugMsg, None, Some(ValidXML.toString())) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg
           |headers=List((X-Request-Chain,$requestChain), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))
           |payload=
           |<Foo>Bar</Foo>""".stripMargin
    }

    "format DEBUG with headers including single overwritten header" in {
      LoggingHelper.formatDebug(debugMsg, LoggingHeadersWithAuth) shouldBe
        s"""[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] $debugMsg
           |headers=List((X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232), (Authorization,Basic YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=))""".stripMargin
    }

  }
}
