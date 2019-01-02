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

import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.RequestChain
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.RequestHeaders._

class NotificationLoggerSpec extends UnitSpec with MockitoSugar {

  trait SetUp {
    val mockCdsLogger: CdsLogger = mock[CdsLogger]
    val logger = new NotificationLogger(mockCdsLogger)
    implicit val hc = HeaderCarrier(extraHeaders = Seq(X_CDS_CLIENT_ID_HEADER, X_CONVERSATION_ID_HEADER), requestChain = RequestChain("rc"))
  }

  "DeclarationsLogger" should {
    "debug(s: => String)" in new SetUp {
      logger.debug("msg")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg\nheaders=List((X-Request-Chain,rc), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))")
        .verify()
    }
    "debug(s: => String, url: => String)" in new SetUp {
      logger.debug("msg", "url")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg url=url\nheaders=List((X-Request-Chain,rc), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))")
        .verify()
    }
    "debug(s: => String, url: => String, payload: => String)" in new SetUp {
      logger.debug("msg", "url", "payload")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg url=url\nheaders=List((X-Request-Chain,rc), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))\npayload=\npayload")
        .verify()
    }
    "debug(s: => String, headers: => SeqOfHeader)" in new SetUp {
      logger.debug("msg", ValidHeaders.toSeq)

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg\nheaders=ArrayBuffer((X-Badge-Identifier,ABCDEF1234), (Accept,application/xml), (Content-Type,application/xml; charset=UTF-8), (X-CDS-Client-ID,ffff01f9-ec3b-4ede-b263-61b626dde232), (X-Eori-Identifier,IAMEORI), (Authorization,Basic YmFzaWN1c2VyOmJhc2ljcGFzc3dvcmQ=), (X-Conversation-ID,eaca01f9-ec3b-4ede-b263-61b626dde232))")
        .verify()
    }
    "info(s: => String)" in new SetUp {
      logger.info("msg")

      PassByNameVerifier(mockCdsLogger, "info")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg")
        .verify()
    }
    "error(s: => String, headers: => SeqOfHeader)" in new SetUp {
      logger.error("msg", ValidHeaders.toSeq)

      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg")
        .verify()
    }
    "error(s: => String)" in new SetUp {
      logger.error("msg")

      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde232][fieldsId=ffff01f9-ec3b-4ede-b263-61b626dde232] msg")
        .verify()
    }
    "debugWithoutRequestContext(s: => String)" in new SetUp {
      logger.debugWithoutRequestContext("msg")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("msg")
        .verify()
    }
  }
}
