package unit.connectors

import uk.gov.hmrc.customs.notification.controllers.FieldsIdMapperHotFix
import util.UnitSpec

class FieldsIdMapperHotFixSpec extends UnitSpec {
  "FieldsIdMapperHotFix" should {

    "map old fields to new one 1" in {
      val oldOne = "c86521a1-3bc3-4408-8ba4-4f51acaeb4d9"
      val newOne = FieldsIdMapperHotFix.workingFieldsId
      assert(newOne == FieldsIdMapperHotFix.translate(oldOne))
    }

    "map old fields to new one 2" in {
      val oldOne = "f964448d-7cf0-444e-9027-172162235dbf"
      val newOne = FieldsIdMapperHotFix.workingFieldsId
      assert(newOne == FieldsIdMapperHotFix.translate(oldOne))
    }

    "map old fields to new one 3" in {
      val oldOne = "8a2e1a95-6240-4256-9439-2ee0c59a16d6"
      val newOne = FieldsIdMapperHotFix.workingFieldsId
      assert(newOne == FieldsIdMapperHotFix.translate(oldOne))
    }

    "map other to itself" in {
      val oldOne = "anyOtherString"
      val newOne = oldOne
      assert(newOne == FieldsIdMapperHotFix.translate(oldOne))
    }




  }
}
