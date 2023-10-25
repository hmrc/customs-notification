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

package uk.gov.hmrc.customs.notification.util

import uk.gov.hmrc.customs.notification.models.{HasId, Mrn}
import uk.gov.hmrc.customs.notification.models.{FunctionCode, IssueDateTime}
import scala.xml.NodeSeq

object Util {
  def createLoggingContext(clientId: String): HasId = {
    new HasId {
      override def idName: String = "clientId"

      override def idValue: String = clientId
    }
  }

  def extractFunctionCode(maybeXml: Option[NodeSeq]): Option[FunctionCode] = {
    maybeXml match {
      case Some(xml) => extractValues(xml \ "Response" \ "FunctionCode").fold {
        val tmp: Option[FunctionCode] = None;
        tmp
      }(x => Some(FunctionCode(x)))
      case _ => None
    }
  }

  def extractIssueDateTime(maybeXml: Option[NodeSeq], maybeDateHeader: Option[String]): Option[IssueDateTime] = {
    //val dateHeader: Option[IssueDateTime] = maybeDateHeader.fold(None)(dateHeader => Some(IssueDateTime(dateHeader)))
    val dateHeader: Option[IssueDateTime] = Some(IssueDateTime("TODO"))

    maybeXml match {
      case Some(xml) => extractValues(xml \ "Response" \ "IssueDateTime" \ "DateTimeString").fold(dateHeader)(dateHeader => Some(IssueDateTime(dateHeader)))
      case _ => dateHeader
    }
  }

  def extractMrn(maybeXml: Option[NodeSeq]): Option[Mrn] = {
    maybeXml match {
      case Some(xml) => extractValues(xml \ "Response" \ "Declaration" \ "ID").fold {
        val tmp: Option[Mrn] = None;
        tmp
      }(x => Some(Mrn(x)))
      case _ => None
    }
  }

  def extractValues(xmlNode: NodeSeq): Option[String] = {
    val values = xmlNode.iterator.collect {
      case node if node.nonEmpty && xmlNode.text.trim.nonEmpty => node.text.trim
    }.mkString("|")
    if (values.isEmpty) None else Some(values)
  }
}
