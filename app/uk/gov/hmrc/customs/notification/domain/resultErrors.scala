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

package uk.gov.hmrc.customs.notification.domain

sealed trait ResultError{
  val cause: Throwable
  def is3xx: Boolean
  def is4xx: Boolean
  def not3xxOr4xx: Boolean
}

case class NonHttpError(cause: Throwable) extends ResultError {
  override def is3xx: Boolean = false
  override def is4xx: Boolean = false
  override def not3xxOr4xx: Boolean = false
}

case class HttpResultError(status: Int, cause: Throwable) extends ResultError {
  def is3xx: Boolean =
    status >= 300 && status < 400
  def is4xx: Boolean =
    status >= 400 && status < 500
  def not3xxOr4xx: Boolean =
    !(is3xx || is4xx)
}
