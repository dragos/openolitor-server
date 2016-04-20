/*                                                                           *\
*    ____                   ____  ___ __                                      *
*   / __ \____  ___  ____  / __ \/ (_) /_____  _____                          *
*  / / / / __ \/ _ \/ __ \/ / / / / / __/ __ \/ ___/   OpenOlitor             *
* / /_/ / /_/ /  __/ / / / /_/ / / / /_/ /_/ / /       contributed by tegonal *
* \____/ .___/\___/_/ /_/\____/_/_/\__/\____/_/        http://openolitor.ch   *
*     /_/                                                                     *
*                                                                             *
* This program is free software: you can redistribute it and/or modify it     *
* under the terms of the GNU General Public License as published by           *
* the Free Software Foundation, either version 3 of the License,              *
* or (at your option) any later version.                                      *
*                                                                             *
* This program is distributed in the hope that it will be useful, but         *
* WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY  *
* or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for *
* more details.                                                               *
*                                                                             *
* You should have received a copy of the GNU General Public License along     *
* with this program. If not, see http://www.gnu.org/licenses/                 *
*                                                                             *
\*                                                                           */
package ch.openolitor.stammdaten.models

import java.util.UUID
import ch.openolitor.core.models._
import java.util.Date
import org.joda.time.DateTime
import ch.openolitor.core.JSONSerializable

case class KundeId(id: Long) extends BaseId

case class Kunde(id: KundeId,
  bezeichnung: String,
  strasse: String,
  hausNummer: Option[String],
  adressZusatz: Option[String],
  plz: String,
  ort: String,
  bemerkungen: Option[String],
  strasseLieferung: Option[String],
  hausNummerLieferung: Option[String],
  adressZusatzLieferung: Option[String],
  plzLieferung: Option[String],
  ortLieferung: Option[String],
  typen: Set[KundentypId],
  //Zusatzinformationen
  anzahlAbos: Int,
  anzahlPendenzen: Int,
  anzahlPersonen: Int,
  //modification flags
  erstelldat: DateTime,
  ersteller: UserId,
  modifidat: DateTime,
  modifikator: UserId) extends BaseEntity[KundeId]

case class KundeDetail(id: KundeId,
  bezeichnung: String,
  strasse: String,
  hausNummer: Option[String],
  adressZusatz: Option[String],
  plz: String,
  ort: String,
  bemerkungen: Option[String],
  strasseLieferung: Option[String],
  hausNummerLieferung: Option[String],
  adressZusatzLieferung: Option[String],
  plzLieferung: Option[String],
  ortLieferung: Option[String],
  typen: Set[KundentypId],
  //Zusatzinformationen
  anzahlAbos: Int,
  anzahlPendenzen: Int,
  anzahlPersonen: Int,
  abos: Seq[Abo],
  pendenzen: Seq[Pendenz],
  ansprechpersonen: Seq[Person],
  //modification flags
  erstelldat: DateTime,
  ersteller: UserId,
  modifidat: DateTime,
  modifikator: UserId) extends JSONSerializable

case class KundeModify(
  bezeichnung: Option[String],
  strasse: String,
  hausNummer: Option[String],
  adressZusatz: Option[String],
  plz: String,
  ort: String,
  bemerkungen: Option[String],
  strasseLieferung: Option[String],
  hausNummerLieferung: Option[String],
  adressZusatzLieferung: Option[String],
  plzLieferung: Option[String],
  ortLieferung: Option[String],
  typen: Set[KundentypId],
  pendenzen: Seq[PendenzModify],
  ansprechpersonen: Seq[PersonModify]) extends JSONSerializable

sealed trait Anrede extends Product
case object Herr extends Anrede
case object Frau extends Anrede

object Anrede {
  def apply(value: String): Anrede = {
    Vector(Herr, Frau).find(_.toString == value).getOrElse(Herr)
  }
}

case class PersonId(id: Long) extends BaseId
case class Person(id: PersonId,
  kundeId: KundeId,
  anrede: Option[Anrede],
  name: String,
  vorname: String,
  email: Option[String],
  emailAlternative: Option[String],
  telefonMobil: Option[String],
  telefonFestnetz: Option[String],
  bemerkungen: Option[String],
  sort: Int,
  //modification flags
  erstelldat: DateTime,
  ersteller: UserId,
  modifidat: DateTime,
  modifikator: UserId) extends BaseEntity[PersonId]

case class KundeSummary(id: KundeId, kunde: String) extends Product

case class PersonModify(
  id: Option[PersonId],
  anrede: Option[Anrede],
  name: String,
  vorname: String,
  email: Option[String],
  emailAlternative: Option[String],
  telefonMobil: Option[String],
  telefonFestnetz: Option[String],
  bemerkungen: Option[String]) extends JSONSerializable {
  def fullName = vorname + ' ' + name
}

case class PersonCreate(
  kundeId: KundeId,
  anrede: Option[Anrede],
  name: String,
  vorname: String,
  email: Option[String],
  emailAlternative: Option[String],
  telefonMobil: Option[String],
  telefonFestnetz: Option[String],
  bemerkungen: Option[String],
  sort: Int) extends JSONSerializable {
  def fullName = vorname + ' ' + name
}

sealed trait PendenzStatus
case object Ausstehend extends PendenzStatus
case object Erledigt extends PendenzStatus
case object NichtErledigt extends PendenzStatus

object PendenzStatus {
  def apply(value: String): PendenzStatus = {
    Vector(Ausstehend, Erledigt, NichtErledigt).find(_.toString == value).getOrElse(Ausstehend)
  }
}

case class PendenzId(id: Long) extends BaseId

case class Pendenz(id: PendenzId,
  kundeId: KundeId,
  kundeBezeichnung: String,
  datum: DateTime,
  bemerkung: Option[String],
  status: PendenzStatus,
  generiert: Boolean,
  //modification flags
  erstelldat: DateTime,
  ersteller: UserId,
  modifidat: DateTime,
  modifikator: UserId) extends BaseEntity[PendenzId]

case class PendenzModify(id: Option[PendenzId],
  datum: DateTime,
  bemerkung: Option[String],
  status: PendenzStatus) extends JSONSerializable
