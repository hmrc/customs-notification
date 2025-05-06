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

import org.mockito.ArgumentMatchers.any
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.controllers.customnotification.RequestMetaData
import uk.gov.hmrc.customs.notification.logging.{CdsLogger, NotificationLogger}
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData.requestMetaData
import util.UnitSpec

class NotificationLoggerSpec extends UnitSpec with MockitoSugar {

  trait SetUp {
    val mockCdsLogger: CdsLogger = mock[CdsLogger]
    val logger = new NotificationLogger(mockCdsLogger)
  }

  private implicit val implicitRequestMetaData: RequestMetaData = requestMetaData

  "DeclarationsLogger" should {
    "debug(s: => String)" in new SetUp {
      logger.debug("msg")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg")
        .verify()
    }
    "debug(s: => String, url: => String)" in new SetUp {
      logger.debug("msg", "url")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg url=url")
        .verify()
    }

    "debug(s: => String, url: => String, payload: => String)" in new SetUp {
      logger.debug("msg", "url", "payload")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg url=url\npayload=\npayload")
        .verify()
    }

    "debugWithoutHeaders(msg: => String, headers: => SeqOfHeader))" in new SetUp {
      logger.debugWithPrefixedHeaders("msg", Seq())

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam(" msg")
        .verify()
    }

    "info(s: => String)" in new SetUp {
      logger.info("msg")

      PassByNameVerifier(mockCdsLogger, "info")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg")
        .verify()
    }

    "warn(s: => String)" in new SetUp {
      logger.warn("msg")

      PassByNameVerifier(mockCdsLogger, "warn")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg")
        .verify()
    }

    "error(s: => String)" in new SetUp {
      logger.error("msg")

      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg")
        .verify()
    }
    
    "error(s: => String, t: => Throwable)" in new SetUp {
      logger.error("msg", new Exception("message"))

      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][clientId=ClientId][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][notificationId=58373a04-2c45-4f43-9ea2-74e56be2c6d7][badgeId=ABCDEF1234][submitterIdentifier=IAMSUBMITTER][correlationId=CORRID2234][functionCode=01][issueDateTime=20190925104103Z][mrn=19GB3955NQ36213969][entryNumber=GB12121212121][ics=1] msg")
        .withByNameParamMatcher[Throwable](any[Throwable])
        .verify()
    }
  }
}
