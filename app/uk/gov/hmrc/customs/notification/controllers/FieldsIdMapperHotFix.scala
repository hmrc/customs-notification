package uk.gov.hmrc.customs.notification.controllers

/**
 * Hot fix for
 * https://jira.tools.tax.service.gov.uk/browse/DCWL-851
 */
object FieldsIdMapperHotFix {

  val workingFieldsId = "0d6d358c-03ec-4e01-a6b1-11d05479c361"

  private val fieldIds = Map(
    "c86521a1-3bc3-4408-8ba4-4f51acaeb4d9" -> workingFieldsId,
    "f964448d-7cf0-444e-9027-172162235dbf" -> workingFieldsId,
    "8a2e1a95-6240-4256-9439-2ee0c59a16d6" -> workingFieldsId,
  )

  def translate(fieldsId: String): String = {
    val safeFieldsID = fieldIds.getOrElse(fieldsId, fieldsId)
    if (safeFieldsID != fieldsId) {
      println(s"FieldsIdMapperHotFix: translating fieldsId [$fieldsId] to [$safeFieldsID].")
    }
    safeFieldsID
  }
}
