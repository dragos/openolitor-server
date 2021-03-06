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
package ch.openolitor.stammdaten

import akka.actor._

import spray.json._
import scalikejdbc._
import ch.openolitor.core.models._
import ch.openolitor.core.domain._
import ch.openolitor.core.ws._
import ch.openolitor.stammdaten.models._
import ch.openolitor.stammdaten.repositories._
import ch.openolitor.core.db._
import ch.openolitor.core.SystemConfig
import ch.openolitor.core.Boot
import ch.openolitor.core.repositories.SqlBinder
import ch.openolitor.core.repositories.BaseEntitySQLSyntaxSupport
import ch.openolitor.buchhaltung.models._
import ch.openolitor.util.IdUtil
import scala.concurrent.ExecutionContext.Implicits.global;
import org.joda.time.DateTime
import org.joda.time.LocalDate
import com.github.nscala_time.time.Imports._
import scala.concurrent.Future
import org.joda.time.format.DateTimeFormat
import BigDecimal.RoundingMode._

object StammdatenDBEventEntityListener extends DefaultJsonProtocol {
  def props(implicit sysConfig: SystemConfig, system: ActorSystem): Props = Props(classOf[DefaultStammdatenDBEventEntityListener], sysConfig, system)
}

class DefaultStammdatenDBEventEntityListener(sysConfig: SystemConfig, override val system: ActorSystem) extends StammdatenDBEventEntityListener(sysConfig) with DefaultStammdatenWriteRepositoryComponent

/**
 * Listen on DBEvents and adjust calculated fields within this module
 */
class StammdatenDBEventEntityListener(override val sysConfig: SystemConfig) extends Actor with ActorLogging
    with StammdatenDBMappings
    with ConnectionPoolContextAware
    with KorbHandler
    with AboAktivChangeHandler
    with LieferungHandler
    with SammelbestellungenHandler {
  this: StammdatenWriteRepositoryComponent =>
  import StammdatenDBEventEntityListener._
  import SystemEvents._

  val dateFormat = DateTimeFormat.forPattern("dd.MM.yyyy")

  override def preStart() {
    super.preStart()
    context.system.eventStream.subscribe(self, classOf[DBEvent[_]])
    context.system.eventStream.subscribe(self, classOf[SystemEvent])
  }

  override def postStop() {
    context.system.eventStream.unsubscribe(self, classOf[DBEvent[_]])
    context.system.eventStream.unsubscribe(self, classOf[SystemEvent])
    super.postStop()
  }

  val receive: Receive = {
    case e @ EntityModified(personId, entity: Abotyp, orig: Abotyp) if entity.name != orig.name =>
      handleAbotypModify(orig, entity)(personId)
    case e @ EntityCreated(personId, entity: DepotlieferungAbo) =>
      handleDepotlieferungAboCreated(entity)(personId)
      handleAboCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: DepotlieferungAbo) =>
      handleDepotlieferungAboDeleted(entity)(personId)
      handleAboDeleted(entity)(personId)
    case e @ EntityModified(personId, entity: DepotlieferungAbo, orig: DepotlieferungAbo) if entity.depotId != orig.depotId =>
      handleDepotlieferungAboDepotChanged(entity, orig.depotId, entity.depotId)(personId)
      handleAboModified(orig, entity)(personId)
    case e @ EntityCreated(personId, entity: HeimlieferungAbo) =>
      handleHeimlieferungAboCreated(entity)(personId)
      handleAboCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: HeimlieferungAbo) =>
      handleHeimlieferungAboDeleted(entity)(personId)
      handleAboDeleted(entity)(personId)
    case e @ EntityModified(personId, entity: HeimlieferungAbo, orig: HeimlieferungAbo) if entity.tourId != orig.tourId =>
      handleHeimlieferungAboTourChanged(entity, orig.tourId, entity.tourId)(personId)
      handleHeimlieferungAboModified(orig, entity)(personId)
      handleAboModified(orig, entity)(personId)
    case e @ EntityModified(personId, entity: HeimlieferungAbo, orig: HeimlieferungAbo) =>
      handleHeimlieferungAboModified(entity, orig)(personId)
      handleAboModified(orig, entity)(personId)
    case e @ EntityModified(personId, entity: PostlieferungAbo, orig: PostlieferungAbo) if entity.vertriebId != orig.vertriebId =>
      handleAboModified(orig, entity)(personId)
    case e @ EntityCreated(personId, entity: Abo) => handleAboCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: Abo) => handleAboDeleted(entity)(personId)
    case e @ EntityModified(personId, entity: Abo, orig: Abo) => handleAboModified(orig, entity)(personId)
    case e @ EntityCreated(personId, entity: Abwesenheit) => handleAbwesenheitCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: Abwesenheit) => handleAbwesenheitDeleted(entity)(personId)

    case e @ EntityCreated(personId, entity: Kunde) => handleKundeCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: Kunde) => handleKundeDeleted(entity)(personId)
    case e @ EntityModified(personId, entity: Kunde, orig: Kunde) => handleKundeModified(entity, orig)(personId)

    case e @ EntityDeleted(personId, entity: Person) => handlePersonDeleted(entity)(personId)

    case e @ EntityCreated(personId, entity: Pendenz) => handlePendenzCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: Pendenz) => handlePendenzDeleted(entity)(personId)
    case e @ EntityModified(personId, entity: Pendenz, orig: Pendenz) => handlePendenzModified(entity, orig)(personId)

    case e @ EntityCreated(personId, entity: Rechnung) => handleRechnungCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: Rechnung) => handleRechnungDeleted(entity)(personId)
    case e @ EntityModified(personId, entity: Rechnung, orig: Rechnung) if (orig.status != Bezahlt && entity.status == Bezahlt) =>
      handleRechnungBezahlt(entity, orig)(personId)
    case e @ EntityModified(personId, entity: Rechnung, orig: Rechnung) if entity.anzahlLieferungen != orig.anzahlLieferungen =>
      handleRechnungGuthabenModified(entity, orig)(personId)

    case e @ EntityCreated(personId, entity: Lieferplanung) => handleLieferplanungCreated(entity)(personId)
    case e @ EntityModified(personId, entity: Lieferplanung, orig: Lieferplanung) if (orig.status != Abgeschlossen && entity.status == Abgeschlossen) =>
      handleLieferplanungAbgeschlossen(entity)(personId)

    case e @ EntityDeleted(personId, entity: Lieferplanung) => handleLieferplanungDeleted(entity)(personId)
    case e @ PersonLoggedIn(personId, timestamp) => handlePersonLoggedIn(personId, timestamp)

    case e @ EntityModified(personId, entity: Lieferplanung, orig: Lieferplanung) =>
      handleLieferplanungModified(entity, orig)(personId)
    case e @ EntityModified(personId, entity: Lieferung, orig: Lieferung) //Die Lieferung wird von der Lieferplanung entfernt
    if (orig.lieferplanungId.isEmpty && entity.lieferplanungId.isDefined) =>
      handleLieferplanungLieferungenChanged(entity.lieferplanungId.get)(personId)
    case e @ EntityModified(personId, entity: Lieferung, orig: Lieferung) //Die Lieferung wird an eine Lieferplanung angehängt
    if (orig.lieferplanungId.isDefined && entity.lieferplanungId.isEmpty) => handleLieferplanungLieferungenChanged(orig.lieferplanungId.get)(personId)

    case e @ EntityModified(personId, entity: Lieferung, orig: Lieferung) if (entity.lieferplanungId.isDefined) => handleLieferungChanged(entity, orig)(personId)

    case e @ EntityModified(personId, entity: Vertriebsart, orig: Vertriebsart) => handleVertriebsartModified(entity, orig)(personId)

    case e @ EntityModified(personId, entity: Auslieferung, orig: Auslieferung) if (orig.status == Erfasst && entity.status == Ausgeliefert) =>
      handleAuslieferungAusgeliefert(entity)(personId)

    case e @ EntityModified(userId, entity: Depot, orig: Depot) => handleDepotModified(entity, orig)(userId)
    case e @ EntityModified(userId, entity: Korb, orig: Korb) if entity.status != orig.status => handleKorbStatusChanged(entity, orig.status)(userId)

    case e @ EntityCreated(personId, entity: Korb) => handleKorbCreated(entity)(personId)
    case e @ EntityDeleted(personId, entity: Korb) => handleKorbDeleted(entity)(personId)

    case x => //log.debug(s"receive unused event $x")
  }

  def handleAbotypModify(orig: Abotyp, entity: Abotyp)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getAbosByAbotyp(entity.id) map { abo =>
        modifyEntity[DepotlieferungAbo, AboId](abo.id, { abo =>
          abo.copy(abotypName = entity.name)
        })
        modifyEntity[HeimlieferungAbo, AboId](abo.id, { abo =>
          abo.copy(abotypName = entity.name)
        })
        modifyEntity[PostlieferungAbo, AboId](abo.id, { abo =>
          abo.copy(abotypName = entity.name)
        })
      }
    }
  }

  def handleVertriebsartModified(vertriebsart: Vertriebsart, orig: Vertriebsart)(implicit personId: PersonId) = {
    //update Beschrieb on Vertrieb
  }

  private def calculateAboAktivCreate(abo: Abo): Int = {
    if (abo.aktiv) 1 else 0
  }

  def handleDepotlieferungAboCreated(abo: DepotlieferungAbo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Depot, DepotId](abo.depotId, { depot =>
        log.debug(s"Add abonnent to depot:${depot.id}")

        depot.copy(anzahlAbonnenten = depot.anzahlAbonnenten + 1, anzahlAbonnentenAktiv = depot.anzahlAbonnentenAktiv + calculateAboAktivCreate(abo))
      })
    }
  }

  def handleDepotlieferungAboDeleted(abo: DepotlieferungAbo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Depot, DepotId](abo.depotId, { depot =>
        log.debug(s"Remove abonnent from depot:${depot.id}")
        depot.copy(anzahlAbonnenten = depot.anzahlAbonnenten - 1, anzahlAbonnentenAktiv = depot.anzahlAbonnentenAktiv - calculateAboAktivCreate(abo))
      })
    }
  }

  def handleDepotlieferungAboDepotChanged(abo: DepotlieferungAbo, from: DepotId, to: DepotId)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Depot, DepotId](from, { depot =>
        log.debug(s"Remove abonnent from depot:${depot.id}")
        depot.copy(anzahlAbonnenten = depot.anzahlAbonnenten - 1, anzahlAbonnentenAktiv = depot.anzahlAbonnentenAktiv - calculateAboAktivCreate(abo))
      })
      modifyEntity[Depot, DepotId](to, { depot =>
        log.debug(s"Add abonnent to depot:${depot.id}")
        depot.copy(anzahlAbonnenten = depot.anzahlAbonnenten + 1, anzahlAbonnentenAktiv = depot.anzahlAbonnentenAktiv + calculateAboAktivCreate(abo))
      })
    }
  }

  def handleHeimlieferungAboModified(entity: HeimlieferungAbo, orig: HeimlieferungAbo)(implicit personId: PersonId) = {
    insertOrUpdateTourlieferung(entity)
  }

  def handleHeimlieferungAboCreated(entity: HeimlieferungAbo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Tour, TourId](entity.tourId, { tour =>
        log.debug(s"Add abonnent to tour:${tour.id}")
        tour.copy(anzahlAbonnenten = tour.anzahlAbonnenten + 1, anzahlAbonnentenAktiv = tour.anzahlAbonnentenAktiv + calculateAboAktivCreate(entity))
      })
    }
    insertOrUpdateTourlieferung(entity)
  }

  def handleHeimlieferungAboDeleted(abo: HeimlieferungAbo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Tour, TourId](abo.tourId, { tour =>
        log.debug(s"Remove abonnent from tour:${tour.id}")
        tour.copy(anzahlAbonnenten = tour.anzahlAbonnenten - 1, anzahlAbonnentenAktiv = tour.anzahlAbonnentenAktiv - calculateAboAktivCreate(abo))
      })

      stammdatenWriteRepository.deleteEntity[Tourlieferung, AboId](abo.id)
    }
  }

  def handleHeimlieferungAboTourChanged(abo: HeimlieferungAbo, from: TourId, to: TourId)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Tour, TourId](from, { tour =>
        log.debug(s"Remove abonnent from tour:${tour.id}")
        tour.copy(anzahlAbonnenten = tour.anzahlAbonnenten - 1, anzahlAbonnentenAktiv = tour.anzahlAbonnentenAktiv - calculateAboAktivCreate(abo))
      })
      modifyEntity[Tour, TourId](to, { tour =>
        log.debug(s"Add abonnent to tour:${tour.id}")
        tour.copy(anzahlAbonnenten = tour.anzahlAbonnenten + 1, anzahlAbonnentenAktiv = tour.anzahlAbonnentenAktiv + calculateAboAktivCreate(abo))
      })
    }
  }

  def handleAboCreated(abo: Abo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      val modAboCount = calculateAboAktivCreate(abo)
      modifyEntity[Abotyp, AbotypId](abo.abotypId, { abotyp =>
        log.debug(s"Add abonnent to abotyp:${abotyp.id}")
        abotyp.copy(anzahlAbonnenten = abotyp.anzahlAbonnenten + 1, anzahlAbonnentenAktiv = abotyp.anzahlAbonnentenAktiv + modAboCount)
      })
      modifyEntity[Kunde, KundeId](abo.kundeId, { kunde =>
        log.debug(s"Add abonnent to kunde:${kunde.id}")
        kunde.copy(anzahlAbos = kunde.anzahlAbos + 1, anzahlAbosAktiv = kunde.anzahlAbosAktiv + modAboCount)
      })
      modifyEntity[Vertrieb, VertriebId](abo.vertriebId, { vertrieb =>
        log.debug(s"Add abonnent to vertrieb:${vertrieb.id}")
        vertrieb.copy(anzahlAbos = vertrieb.anzahlAbos + 1, anzahlAbosAktiv = vertrieb.anzahlAbosAktiv + modAboCount)
      })
      modifyEntity[Depotlieferung, VertriebsartId](abo.vertriebsartId, { vertriebsart =>
        log.debug(s"Add abonnent to vertriebsart:${vertriebsart.id}")
        vertriebsart.copy(anzahlAbos = vertriebsart.anzahlAbos + 1, anzahlAbosAktiv = vertriebsart.anzahlAbosAktiv + modAboCount)
      })
      modifyEntity[Heimlieferung, VertriebsartId](abo.vertriebsartId, { vertriebsart =>
        log.debug(s"Add abonnent to vertriebsart:${vertriebsart.id}")
        vertriebsart.copy(anzahlAbos = vertriebsart.anzahlAbos + 1, anzahlAbosAktiv = vertriebsart.anzahlAbosAktiv + modAboCount)
      })
      modifyEntity[Postlieferung, VertriebsartId](abo.vertriebsartId, { vertriebsart =>
        log.debug(s"Add abonnent to vertriebsart:${vertriebsart.id}")
        vertriebsart.copy(anzahlAbos = vertriebsart.anzahlAbos + 1, anzahlAbosAktiv = vertriebsart.anzahlAbosAktiv + modAboCount)
      })
      createKoerbeForAbo(abo)
    }
  }

  def handleAboModified(from: Abo, to: Abo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      if (from.aktiv != to.aktiv) {
        handleAboAktivChange(to, if (to.aktiv) 1 else -1)
      }

      if (from.vertriebId != to.vertriebId) {
        val modAboCount = calculateAboAktivCreate(to)
        modifyEntity[Vertrieb, VertriebId](from.vertriebId, { vertrieb =>
          log.debug(s"Remove abonnent from vertrieb:${vertrieb.id}")
          vertrieb.copy(anzahlAbos = vertrieb.anzahlAbos - 1, anzahlAbosAktiv = vertrieb.anzahlAbosAktiv - modAboCount)
        })
        modifyEntity[Vertrieb, VertriebId](to.vertriebId, { vertrieb =>
          log.debug(s"Add abonnent to vertrieb:${vertrieb.id}")
          vertrieb.copy(anzahlAbos = vertrieb.anzahlAbos + 1, anzahlAbosAktiv = vertrieb.anzahlAbosAktiv + modAboCount)
        })
      }

      modifyKoerbeForAbo(to, Some(from))
    }
  }

  def handleAboDeleted(abo: Abo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      val modAboCount = calculateAboAktivCreate(abo)
      modifyEntity[Abotyp, AbotypId](abo.abotypId, { abotyp =>
        log.debug(s"Remove abonnent from abotyp:${abotyp.id}")
        abotyp.copy(anzahlAbonnenten = abotyp.anzahlAbonnenten - 1, anzahlAbonnentenAktiv = abotyp.anzahlAbonnentenAktiv - modAboCount)
      })
      modifyEntity[Kunde, KundeId](abo.kundeId, { kunde =>
        log.debug(s"Remove abonnent from kunde:${kunde.id}")
        kunde.copy(anzahlAbos = kunde.anzahlAbos - 1, anzahlAbosAktiv = kunde.anzahlAbosAktiv - modAboCount)
      })
      modifyEntity[Vertrieb, VertriebId](abo.vertriebId, { vertrieb =>
        log.debug(s"Remove abonnent from vertrieb:${vertrieb.id}")
        vertrieb.copy(anzahlAbos = vertrieb.anzahlAbos - 1, anzahlAbosAktiv = vertrieb.anzahlAbosAktiv - modAboCount)
      })
      modifyEntity[Depotlieferung, VertriebsartId](abo.vertriebsartId, { vertriebsart =>
        log.debug(s"Remove abonnent from vertriebsart:${vertriebsart.id}")
        vertriebsart.copy(anzahlAbos = vertriebsart.anzahlAbos - 1, anzahlAbosAktiv = vertriebsart.anzahlAbosAktiv - modAboCount)
      })
      modifyEntity[Heimlieferung, VertriebsartId](abo.vertriebsartId, { vertriebsart =>
        log.debug(s"Remove abonnent from vertriebsart:${vertriebsart.id}")
        vertriebsart.copy(anzahlAbos = vertriebsart.anzahlAbos - 1, anzahlAbosAktiv = vertriebsart.anzahlAbosAktiv - modAboCount)
      })
      modifyEntity[Postlieferung, VertriebsartId](abo.vertriebsartId, { vertriebsart =>
        log.debug(s"Remove abonnent from vertriebsart:${vertriebsart.id}")
        vertriebsart.copy(anzahlAbos = vertriebsart.anzahlAbos - 1, anzahlAbosAktiv = vertriebsart.anzahlAbosAktiv - modAboCount)
      })

      deleteKoerbeForDeletedAbo(abo)
    }
  }

  def createKoerbeForAbo(abo: Abo)(implicit personId: PersonId, session: DBSession) = {
    modifyKoerbeForAbo(abo, None)
  }

  def modifyKoerbeForAbo(abo: Abo, orig: Option[Abo])(implicit personId: PersonId, session: DBSession) = {
    // koerbe erstellen, modifizieren, loeschen falls noetig
    val isExistingAbo = orig.isDefined
    // only modify koerbe if the start or end of this abo has changed or we're creating them for a new abo
    if (!isExistingAbo || abo.start != orig.get.start || abo.ende != orig.get.ende) {
      stammdatenWriteRepository.getById(abotypMapping, abo.abotypId) map { abotyp =>
        stammdatenWriteRepository.getLieferungenOffenByAbotyp(abo.abotypId) map { lieferung =>
          if (isExistingAbo && (abo.start > lieferung.datum.toLocalDate || (abo.ende map (_ <= (lieferung.datum.toLocalDate - 1.day)) getOrElse false))) {
            deleteKorb(lieferung, abo)
          } else if (abo.start <= lieferung.datum.toLocalDate && (abo.ende map (_ >= lieferung.datum.toLocalDate) getOrElse true)) {
            upsertKorb(lieferung, abo, abotyp) match {
              case (Some(created), None) =>
                // nur im created Fall muss eins dazu gezählt werden
                // bei Statuswechsel des Korbs wird handleKorbStatusChanged die Counts justieren
                updateLieferungWithCount(created, 1)
              case _ =>
              // counts werden andersweitig angepasst
            }
          }
        }
      }
    }
  }

  def deleteKoerbeForDeletedAbo(abo: Abo)(implicit personId: PersonId, session: DBSession) = {
    // Koerbe der offenen Lieferungen loeschen
    stammdatenWriteRepository.getLieferungenOffenByAbotyp(abo.abotypId) map { lieferung =>
      deleteKorb(lieferung, abo)
    }
  }

  def handleKorbDeleted(korb: Korb)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      updateLieferungWithCount(korb, -1)
    }
  }

  def handleKorbCreated(korb: Korb)(implicit personId: PersonId) = {
    // Lieferung Counts bereits gesetzt im InsertService
  }

  private def updateLieferungWithCount(korb: Korb, add: Int)(implicit personId: PersonId, session: DBSession) = {
    stammdatenWriteRepository.getById(lieferungMapping, korb.lieferungId) map { lieferung =>
      val copy = lieferung.copy(
        anzahlKoerbeZuLiefern = if (WirdGeliefert == korb.status) lieferung.anzahlKoerbeZuLiefern + add else lieferung.anzahlKoerbeZuLiefern,
        anzahlAbwesenheiten = if (FaelltAusAbwesend == korb.status) lieferung.anzahlAbwesenheiten + add else lieferung.anzahlAbwesenheiten,
        anzahlSaldoZuTief = if (FaelltAusSaldoZuTief == korb.status) lieferung.anzahlSaldoZuTief + add else lieferung.anzahlSaldoZuTief
      )

      stammdatenWriteRepository.updateEntity[Lieferung, LieferungId](copy, lieferungMapping.column.anzahlKoerbeZuLiefern, lieferungMapping.column.anzahlAbwesenheiten, lieferungMapping.column.anzahlSaldoZuTief)
    }
  }

  def handleKundeModified(kunde: Kunde, orig: Kunde)(implicit personId: PersonId) = {
    //compare typen
    //find removed typen
    val removed = orig.typen -- kunde.typen

    //tag typen which where added
    val added = kunde.typen -- orig.typen

    log.debug(s"Kunde ${kunde.bezeichnung} modified, handle CustomKundentypen. Orig: ${orig.typen} -> modified: ${kunde.typen}. Removed typen:${removed}, added typen:${added}")

    handleKundentypenChanged(removed, added)

    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getPendenzen(kunde.id) map { pendenz =>
        val copy = pendenz.copy(kundeBezeichnung = kunde.bezeichnung)
        log.debug(s"Modify Kundenbezeichnung on Pendenz to : ${copy.kundeBezeichnung}.")
        stammdatenWriteRepository.updateEntity[Pendenz, PendenzId](copy)
      }
    }

    insertOrUpdateTourlieferungenByKunde(kunde)
  }

  def handleKundeDeleted(kunde: Kunde)(implicit personId: PersonId) = {
    handleKundentypenChanged(kunde.typen, Set())
  }

  def handleKundeCreated(kunde: Kunde)(implicit personId: PersonId) = {
    handleKundentypenChanged(Set(), kunde.typen)
  }

  def handlePersonDeleted(person: Person)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      val personen = stammdatenWriteRepository.getPersonen(person.kundeId)
      if (personen.size == 1) {
        stammdatenWriteRepository.getById(kundeMapping, person.kundeId) map { kunde =>
          val copy = kunde.copy(bezeichnung = personen.head.fullName)
          log.debug(s"Kunde-Bezeichnung set to empty as there is just one Person: ${kunde.id}")
          stammdatenWriteRepository.updateEntity[Kunde, KundeId](copy)
        }
      }
      // Recalculate sort field on Persons
      personen.zipWithIndex.map {
        case (person, index) =>
          val sortValue = (index + 1)
          if (sortValue != person.sort) {
            logger.debug(s"Update sort for Person {person.id} {person.vorname} {person.name} to {sortValue}")
            val copy = person.copy(sort = sortValue)
            stammdatenWriteRepository.updateEntity[Person, PersonId](copy)
          }
      }
    }
  }

  def handleAbwesenheitDeleted(abw: Abwesenheit)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getProjekt map { projekt =>
        val geschaeftsjahrKey = projekt.geschaftsjahr.key(abw.datum)

        modifyEntity[DepotlieferungAbo, AboId](abw.aboId, { abo =>
          val value = Math.max(abo.anzahlAbwesenheiten.get(geschaeftsjahrKey).map(_ - 1).getOrElse(0), 0)
          log.debug(s"Remove abwesenheit from abo:${abo.id}, new value:$value")
          abo.copy(anzahlAbwesenheiten = abo.anzahlAbwesenheiten.updated(geschaeftsjahrKey, value))
        })
        modifyEntity[HeimlieferungAbo, AboId](abw.aboId, { abo =>
          val value = Math.max(abo.anzahlAbwesenheiten.get(geschaeftsjahrKey).map(_ - 1).getOrElse(0), 0)
          log.debug(s"Remove abwesenheit from abo:${abo.id}, new value:$value")
          abo.copy(anzahlAbwesenheiten = abo.anzahlAbwesenheiten.updated(geschaeftsjahrKey, value))
        })
        modifyEntity[PostlieferungAbo, AboId](abw.aboId, { abo =>
          val value = Math.max(abo.anzahlAbwesenheiten.get(geschaeftsjahrKey).map(_ - 1).getOrElse(0), 0)
          log.debug(s"Remove abwesenheit from abo:${abo.id}, new value:$value")
          abo.copy(anzahlAbwesenheiten = abo.anzahlAbwesenheiten.updated(geschaeftsjahrKey, value))
        })
      }

      stammdatenWriteRepository.getAboDetailAusstehend(abw.aboId) match {
        case Some(abo) => {
          stammdatenWriteRepository.getKorb(abw.lieferungId, abw.aboId) match {
            case Some(korb) => {
              stammdatenWriteRepository.getById(abotypMapping, abo.abotypId) map { abotyp =>
                //re count because the might be another abwesenheit for the same date
                val newAbwesenheitCount = stammdatenWriteRepository.countAbwesend(abw.aboId, abw.datum)
                val status = calculateKorbStatus(newAbwesenheitCount, abo.guthaben, abotyp.guthabenMindestbestand)
                val statusAlt = korb.status
                val copy = korb.copy(status = status)
                log.debug(s"Modify Korb-Status as Abwesenheit was deleted ${abw.id}, newCount:$newAbwesenheitCount, newStatus:${copy.status}.")
                stammdatenWriteRepository.updateEntity[Korb, KorbId](copy)
              }
            }
            case None => log.debug(s"No Korb yet for Lieferung : ${abw.lieferungId} and Abotyp : ${abw.aboId}")
          }
        }
        case None => log.error(s"There should be an abo with this id : ${abw.aboId}")
      }
    }
  }

  def handleAbwesenheitCreated(abw: Abwesenheit)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getProjekt map { projekt =>
        val geschaeftsjahrKey = projekt.geschaftsjahr.key(abw.datum)

        modifyEntity[DepotlieferungAbo, AboId](abw.aboId, { abo =>
          val value = abo.anzahlAbwesenheiten.get(geschaeftsjahrKey).map(_ + 1).getOrElse(1)
          log.debug(s"Add abwesenheit to abo:${abo.id}, new value:$value, values:${abo.anzahlAbwesenheiten}")
          abo.copy(anzahlAbwesenheiten = abo.anzahlAbwesenheiten.updated(geschaeftsjahrKey, value))
        })
        modifyEntity[HeimlieferungAbo, AboId](abw.aboId, { abo =>
          val value = abo.anzahlAbwesenheiten.get(geschaeftsjahrKey).map(_ + 1).getOrElse(1)
          log.debug(s"Add abwesenheit to abo:${abo.id}, new value:$value, values:${abo.anzahlAbwesenheiten}")
          abo.copy(anzahlAbwesenheiten = abo.anzahlAbwesenheiten.updated(geschaeftsjahrKey, value))
        })
        modifyEntity[PostlieferungAbo, AboId](abw.aboId, { abo =>
          val value = abo.anzahlAbwesenheiten.get(geschaeftsjahrKey).map(_ + 1).getOrElse(1)
          log.debug(s"Add abwesenheit to abo:${abo.id}, new value:$value, values:${abo.anzahlAbwesenheiten}")
          abo.copy(anzahlAbwesenheiten = abo.anzahlAbwesenheiten.updated(geschaeftsjahrKey, value))
        })
      }

      stammdatenWriteRepository.getKorb(abw.lieferungId, abw.aboId) match {
        case Some(korb) => {
          val statusAlt = korb.status
          val status = FaelltAusAbwesend
          val copy = korb.copy(status = status)
          log.debug(s"Modify Korb-Status as Abwesenheit was created : ${copy}.")
          stammdatenWriteRepository.updateEntity[Korb, KorbId](copy)

        }
        case None => log.debug(s"No Korb yet for Lieferung : ${abw.lieferungId} and Abotyp : ${abw.aboId}")
      }
    }
  }

  def handleKorbStatusChanged(korb: Korb, statusAlt: KorbStatus)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getById(lieferungMapping, korb.lieferungId) map { lieferung =>
        log.debug(s"Korb Status changed:${korb.aboId}/${korb.lieferungId}")
        stammdatenWriteRepository.updateEntity[Lieferung, LieferungId](recalculateLieferungCounts(lieferung, korb.status, statusAlt), lieferungMapping.column.anzahlKoerbeZuLiefern, lieferungMapping.column.anzahlAbwesenheiten, lieferungMapping.column.anzahlSaldoZuTief)
      }
    }
  }

  private def recalculateLieferungCounts(lieferung: Lieferung, korbStatusNeu: KorbStatus, korbStatusAlt: KorbStatus)(implicit personId: PersonId, session: DBSession) = {
    val zuLiefernDiff = korbStatusNeu match {
      case WirdGeliefert => 1
      case Geliefert if korbStatusAlt == WirdGeliefert => 0 // TODO introduce additional counter for delivered baskets
      case _ if korbStatusAlt == WirdGeliefert => -1
      case _ => 0
    }
    val abwDiff = korbStatusNeu match {
      case FaelltAusAbwesend => 1
      case _ if korbStatusAlt == FaelltAusAbwesend => -1
      case _ => 0
    }
    val saldoDiff = korbStatusNeu match {
      case FaelltAusSaldoZuTief => 1
      case _ if korbStatusAlt == FaelltAusSaldoZuTief => -1
      case _ => 0
    }

    val copy = lieferung.copy(
      anzahlKoerbeZuLiefern = lieferung.anzahlKoerbeZuLiefern + zuLiefernDiff,
      anzahlAbwesenheiten = lieferung.anzahlAbwesenheiten + abwDiff,
      anzahlSaldoZuTief = lieferung.anzahlSaldoZuTief + saldoDiff
    )
    log.debug(s"Recalculate Lieferung as Korb-Status: was modified : ${lieferung.id} status form ${korbStatusAlt} to ${korbStatusNeu}. zu lieferung:$zuLiefernDiff, Abw: $abwDiff, Saldo: $saldoDiff\nfrom:$lieferung\nto:$copy")
    copy

  }

  def handlePendenzCreated(pendenz: Pendenz)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Kunde, KundeId](pendenz.kundeId, { kunde =>
        log.debug(s"Add pendenz count to kunde:${kunde.id}")
        kunde.copy(anzahlPendenzen = kunde.anzahlPendenzen + 1)
      })
    }
  }

  def handlePendenzDeleted(pendenz: Pendenz)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[Kunde, KundeId](pendenz.kundeId, { kunde =>
        log.debug(s"Remove pendenz count from kunde:${kunde.id}")
        kunde.copy(anzahlPendenzen = kunde.anzahlPendenzen - 1)
      })
    }
  }

  def handlePendenzModified(pendenz: Pendenz, orig: Pendenz)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      if (pendenz.status == Erledigt && orig.status != Erledigt) {
        modifyEntity[Kunde, KundeId](pendenz.kundeId, { kunde =>
          log.debug(s"Remove pendenz count from kunde:${kunde.id}")
          kunde.copy(anzahlPendenzen = kunde.anzahlPendenzen - 1)
        })
      } else if (pendenz.status != Erledigt && orig.status == Erledigt) {
        modifyEntity[Kunde, KundeId](pendenz.kundeId, { kunde =>
          log.debug(s"Remove pendenz count from kunde:${kunde.id}")
          kunde.copy(anzahlPendenzen = kunde.anzahlPendenzen + 1)
        })
      }
    }
  }

  def handleKundentypenChanged(removed: Set[KundentypId], added: Set[KundentypId])(implicit personId: PersonId) = {

    // Only count if it is not a rename of a Kundentyp
    DB autoCommit { implicit session =>

      val kundetypen = stammdatenWriteRepository.getCustomKundentypen
      val kundentypenSet: Set[KundentypId] = kundetypen.map(_.kundentyp).toSet
      val rename = removed.size == 1 &&
        added.size == 1 &&
        kundentypenSet.intersect(removed).size == 0

      if (!rename) {
        removed.map { kundetypId =>
          kundetypen.filter(kt => kt.kundentyp == kundetypId).headOption.map {
            case customKundentyp: CustomKundentyp =>
              val copy = customKundentyp.copy(anzahlVerknuepfungen = customKundentyp.anzahlVerknuepfungen - 1)
              log.debug(s"Reduce anzahlVerknuepfung on CustomKundentyp: ${customKundentyp.kundentyp}. New count:${copy.anzahlVerknuepfungen}")
              stammdatenWriteRepository.updateEntity[CustomKundentyp, CustomKundentypId](copy)
          }
        }
        added.map { kundetypId =>
          kundetypen.filter(kt => kt.kundentyp == kundetypId).headOption.map {
            case customKundentyp: CustomKundentyp =>
              val copy = customKundentyp.copy(anzahlVerknuepfungen = customKundentyp.anzahlVerknuepfungen + 1)
              log.debug(s"Increment anzahlVerknuepfung on CustomKundentyp: ${customKundentyp.kundentyp}. New count:${copy.anzahlVerknuepfungen}")
              stammdatenWriteRepository.updateEntity[CustomKundentyp, CustomKundentypId](copy)
          }
        }
      }
    }
  }

  def handleRechnungDeleted(rechnung: Rechnung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      adjustGuthabenInRechnung(rechnung.aboId, 0 - rechnung.anzahlLieferungen)
    }
  }

  def handleRechnungCreated(rechnung: Rechnung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      adjustGuthabenInRechnung(rechnung.aboId, rechnung.anzahlLieferungen)
    }
  }

  def handleRechnungBezahlt(rechnung: Rechnung, orig: Rechnung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      modifyEntity[DepotlieferungAbo, AboId](rechnung.aboId, { abo =>
        abo.copy(
          guthabenInRechnung = abo.guthabenInRechnung - rechnung.anzahlLieferungen,
          guthaben = abo.guthaben + rechnung.anzahlLieferungen,
          guthabenVertraglich = abo.guthabenVertraglich map (_ - rechnung.anzahlLieferungen) orElse (None)
        )
      })
      modifyEntity[PostlieferungAbo, AboId](rechnung.aboId, { abo =>
        abo.copy(
          guthabenInRechnung = abo.guthabenInRechnung - rechnung.anzahlLieferungen,
          guthaben = abo.guthaben + rechnung.anzahlLieferungen,
          guthabenVertraglich = abo.guthabenVertraglich map (_ - rechnung.anzahlLieferungen) orElse (None)
        )
      })
      modifyEntity[HeimlieferungAbo, AboId](rechnung.aboId, { abo =>
        abo.copy(
          guthabenInRechnung = abo.guthabenInRechnung - rechnung.anzahlLieferungen,
          guthaben = abo.guthaben + rechnung.anzahlLieferungen,
          guthabenVertraglich = abo.guthabenVertraglich map (_ - rechnung.anzahlLieferungen) orElse (None)
        )
      })
    }
  }

  def handleRechnungGuthabenModified(rechnung: Rechnung, orig: Rechnung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      adjustGuthabenInRechnung(rechnung.aboId, rechnung.anzahlLieferungen - orig.anzahlLieferungen)
    }
  }

  private def adjustGuthabenInRechnung(aboId: AboId, diff: Int)(implicit personId: PersonId, session: DBSession) = {
    modifyEntity[DepotlieferungAbo, AboId](aboId, { abo =>
      abo.copy(
        guthabenInRechnung = abo.guthabenInRechnung + diff
      )
    })
    modifyEntity[PostlieferungAbo, AboId](aboId, { abo =>
      abo.copy(
        guthabenInRechnung = abo.guthabenInRechnung + diff
      )
    })
    modifyEntity[HeimlieferungAbo, AboId](aboId, { abo =>
      abo.copy(
        guthabenInRechnung = abo.guthabenInRechnung + diff
      )
    })
  }

  def handleLieferplanungCreated(lieferplanung: Lieferplanung)(implicit personId: PersonId) = {
  }

  def handleLieferplanungDeleted(lieferplanung: Lieferplanung)(implicit personId: PersonId) = {

  }

  def handleLieferplanungAbgeschlossen(lieferplanung: Lieferplanung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      val lieferungen = stammdatenWriteRepository.getLieferungen(lieferplanung.id)

      //handle Tourenlieferungen: Group all entries with the same TourId on the same Date
      val vertriebsartenDaten = (lieferungen flatMap { lieferung =>
        stammdatenWriteRepository.getVertriebsarten(lieferung.vertriebId) collect {
          case h: HeimlieferungDetail =>
            stammdatenWriteRepository.getById(tourMapping, h.tourId) map { tour =>
              (h.tourId, tour.name, lieferung.datum) -> h.id
            }
        }
      }).flatten.groupBy(_._1).mapValues(_ map { _._2 })

      vertriebsartenDaten map {
        case ((tourId, tourName, lieferdatum), vertriebsartIds) => {
          //create auslieferungen
          if (!isAuslieferungExistingHeim(lieferdatum, tourId)) {
            val koerbe = stammdatenWriteRepository.getKoerbe(lieferdatum, vertriebsartIds, WirdGeliefert)
            if (!koerbe.isEmpty) {
              val auslieferung = createAuslieferungHeim(lieferdatum, tourId, tourName, koerbe.size)

              koerbe map { korb =>
                val tourlieferung = stammdatenWriteRepository.getById[Tourlieferung, AboId](tourlieferungMapping, korb.aboId)
                val copy = korb.copy(auslieferungId = Some(auslieferung.id), sort = tourlieferung flatMap (_.sort))
                stammdatenWriteRepository.updateEntity[Korb, KorbId](copy)
              }
            }
          }
        }
      }

      //handle Depot- and Postlieferungen: Group all entries with the same VertriebId on the same Date
      val vertriebeDaten = lieferungen.map(l => (l.vertriebId, l.datum)).distinct

      vertriebeDaten map {
        case (vertriebId, lieferungDatum) => {

          log.debug(s"handleLieferplanungAbgeschlossen (Depot & Post): ${vertriebId}:${lieferungDatum}.")
          //create auslieferungen
          val auslieferungL = stammdatenWriteRepository.getVertriebsarten(vertriebId) map { vertriebsart =>

            val auslieferungO = getAuslieferungDepotPost(lieferungDatum, vertriebsart)

            val auslieferungC = auslieferungO match {
              case Some(auslieferung) => {
                Some(auslieferung)
              }
              case None => {
                log.debug(s"createNewAuslieferung for: ${lieferungDatum}:${vertriebsart}.")
                createAuslieferungDepotPost(lieferungDatum, vertriebsart, 0)
              }
            }

            auslieferungC map { _ =>
              val koerbe = stammdatenWriteRepository.getKoerbe(lieferungDatum, vertriebsart.id, WirdGeliefert)
              if (!koerbe.isEmpty) {
                koerbe map { korb =>
                  val copy = korb.copy(auslieferungId = Some(auslieferungC.head.id))
                  stammdatenWriteRepository.updateEntity[Korb, KorbId](copy)
                }
              }
            }
            auslieferungC
          }

          auslieferungL.distinct.collect {
            case Some(auslieferung) => {
              val koerbeC = stammdatenWriteRepository.countKoerbe(auslieferung.id) getOrElse 0
              auslieferung match {
                case d: DepotAuslieferung => {
                  val copy = d.copy(anzahlKoerbe = koerbeC)
                  stammdatenWriteRepository.updateEntity[DepotAuslieferung, AuslieferungId](copy)
                }
                case p: PostAuslieferung => {
                  val copy = p.copy(anzahlKoerbe = koerbeC)
                  stammdatenWriteRepository.updateEntity[PostAuslieferung, AuslieferungId](copy)
                }
                case _ =>
              }
            }
          }
        }
      }
      //calculate new values
      lieferungen map { lieferung =>
        //calculate total of lieferung
        val total = stammdatenWriteRepository.getLieferpositionenByLieferung(lieferung.id).map(_.preis.getOrElse(0.asInstanceOf[BigDecimal])).sum
        val lieferungCopy = lieferung.copy(preisTotal = total, status = Abgeschlossen)
        stammdatenWriteRepository.updateEntity[Lieferung, LieferungId](lieferungCopy, lieferungMapping.column.preisTotal, lieferungMapping.column.status)

        //update durchschnittspreis
        stammdatenWriteRepository.getProjekt map { projekt =>
          stammdatenWriteRepository.getVertrieb(lieferung.vertriebId) map { vertrieb =>
            val gjKey = projekt.geschaftsjahr.key(lieferung.datum.toLocalDate)

            val lieferungen = vertrieb.anzahlLieferungen.get(gjKey).getOrElse(0)
            val durchschnittspreis: BigDecimal = vertrieb.durchschnittspreis.get(gjKey).getOrElse(0)

            val neuerDurchschnittspreis = calcDurchschnittspreis(durchschnittspreis, lieferungen, total)
            val copy = vertrieb.copy(
              anzahlLieferungen = vertrieb.anzahlLieferungen.updated(gjKey, lieferungen + 1),
              durchschnittspreis = vertrieb.durchschnittspreis.updated(gjKey, neuerDurchschnittspreis)
            )

            stammdatenWriteRepository.updateEntity[Vertrieb, VertriebId](copy)
          }
        }
      }

      stammdatenWriteRepository.getSammelbestellungen(lieferplanung.id) map { sammelbestellung =>
        if (Offen == sammelbestellung.status) {
          stammdatenWriteRepository.updateEntity[Sammelbestellung, SammelbestellungId](sammelbestellung.copy(status = Abgeschlossen))
        }
      }
    }
  }

  private def isAuslieferungExistingHeim(datum: DateTime, tourId: TourId)(implicit session: DBSession): Boolean = {
    stammdatenWriteRepository.getTourAuslieferung(tourId, datum).isDefined
  }

  private def getAuslieferungDepotPost(datum: DateTime, vertriebsart: VertriebsartDetail)(implicit session: DBSession): Option[Auslieferung] = {
    vertriebsart match {
      case d: DepotlieferungDetail =>
        stammdatenWriteRepository.getDepotAuslieferung(d.depotId, datum)
      case p: PostlieferungDetail =>
        stammdatenWriteRepository.getPostAuslieferung(datum)
      case _ =>
        None
    }
  }

  private def createAuslieferungHeim(lieferungDatum: DateTime, tourId: TourId, tourName: String, anzahlKoerbe: Int)(implicit personId: PersonId, session: DBSession): Auslieferung = {
    val auslieferungId = AuslieferungId(IdUtil.positiveRandomId)

    val result = TourAuslieferung(
      auslieferungId,
      Erfasst,
      tourId,
      tourName,
      lieferungDatum,
      anzahlKoerbe,
      DateTime.now,
      personId,
      DateTime.now,
      personId
    )
    stammdatenWriteRepository.insertEntity[TourAuslieferung, AuslieferungId](result)

    result
  }

  private def createAuslieferungDepotPost(lieferungDatum: DateTime, vertriebsart: VertriebsartDetail, anzahlKoerbe: Int)(implicit personId: PersonId, session: DBSession): Option[Auslieferung] = {
    val auslieferungId = AuslieferungId(IdUtil.positiveRandomId)

    vertriebsart match {
      case d: DepotlieferungDetail =>
        val result = DepotAuslieferung(
          auslieferungId,
          Erfasst,
          d.depotId,
          d.depot.name,
          lieferungDatum,
          anzahlKoerbe,
          DateTime.now,
          personId,
          DateTime.now,
          personId
        )
        stammdatenWriteRepository.insertEntity[DepotAuslieferung, AuslieferungId](result)
        Some(result)

      case p: PostlieferungDetail =>
        val result = PostAuslieferung(
          auslieferungId,
          Erfasst,
          lieferungDatum,
          anzahlKoerbe,
          DateTime.now,
          personId,
          DateTime.now,
          personId
        )
        stammdatenWriteRepository.insertEntity[PostAuslieferung, AuslieferungId](result)
        Some(result)
      case _ =>
        //nothing to create for Tour, see createAuslieferungHeim
        None
    }
  }

  def handleAuslieferungAusgeliefert(entity: Auslieferung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getKoerbe(entity.id) map { korb =>
        val copy = korb.copy(status = Geliefert)
        stammdatenWriteRepository.updateEntity[Korb, KorbId](copy)
        stammdatenWriteRepository.getProjekt map { projekt =>
          val geschaeftsjahrKey = projekt.geschaftsjahr.key(entity.datum.toLocalDate)

          modifyEntity[DepotlieferungAbo, AboId](korb.aboId, { abo =>
            val value = abo.anzahlLieferungen.get(geschaeftsjahrKey).map(_ + 1).getOrElse(1)
            updateAbotypOnAusgeliefert(abo.abotypId, entity.datum)
            abo.copy(guthaben = korb.guthabenVorLieferung - 1, letzteLieferung = getLatestDate(abo.letzteLieferung, Some(entity.datum)),
              anzahlLieferungen = abo.anzahlLieferungen.updated(geschaeftsjahrKey, value))
          })
          modifyEntity[HeimlieferungAbo, AboId](korb.aboId, { abo =>
            val value = abo.anzahlLieferungen.get(geschaeftsjahrKey).map(_ + 1).getOrElse(1)
            updateAbotypOnAusgeliefert(abo.abotypId, entity.datum)
            abo.copy(guthaben = korb.guthabenVorLieferung - 1, letzteLieferung = getLatestDate(abo.letzteLieferung, Some(entity.datum)),
              anzahlLieferungen = abo.anzahlLieferungen.updated(geschaeftsjahrKey, value))
          })
          modifyEntity[PostlieferungAbo, AboId](korb.aboId, { abo =>
            val value = abo.anzahlLieferungen.get(geschaeftsjahrKey).map(_ + 1).getOrElse(1)
            updateAbotypOnAusgeliefert(abo.abotypId, entity.datum)
            abo.copy(guthaben = korb.guthabenVorLieferung - 1, letzteLieferung = getLatestDate(abo.letzteLieferung, Some(entity.datum)),
              anzahlLieferungen = abo.anzahlLieferungen.updated(geschaeftsjahrKey, value))
          })
        }
      }
    }
  }

  private def updateAbotypOnAusgeliefert(abotypId: AbotypId, letzteLieferung: DateTime)(implicit personId: PersonId, dbSession: DBSession) = {
    modifyEntity[Abotyp, AbotypId](abotypId, { abotyp =>
      abotyp.copy(letzteLieferung = getLatestDate(abotyp.letzteLieferung, Some(letzteLieferung)))
    })
  }

  private def getLatestDate(date1: Option[DateTime], date2: Option[DateTime]): Option[DateTime] = {
    if (date2 == None || (date1 != None && date1.get.isAfter(date2.get))) {
      date1
    } else {
      date2
    }
  }

  def handleDepotModified(depot: Depot, orig: Depot)(implicit personId: PersonId) = {
    logger.debug(s"handleDepotModified: depot:$depot  orig:$orig")
    if (depot.name != orig.name) {
      //Depot name was changed. Replace it in Abos
      DB autoCommit { implicit session =>
        stammdatenWriteRepository.getDepotlieferungAbosByDepot(depot.id) map { abo =>
          val copy = abo.copy(
            depotName = depot.name
          )
          stammdatenWriteRepository.updateEntity[DepotlieferungAbo, AboId](copy)
        }
      }
    }
  }

  def insertOrUpdateTourlieferung(entity: HeimlieferungAbo)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getById(kundeMapping, entity.kundeId) map { kunde =>
        val updated = Tourlieferung(
          entity.id,
          entity.tourId,
          entity.abotypId,
          entity.kundeId,
          entity.vertriebsartId,
          entity.vertriebId,
          kunde.bezeichnungLieferung getOrElse kunde.bezeichnung,
          kunde.strasseLieferung getOrElse kunde.strasse,
          kunde.hausNummerLieferung orElse kunde.hausNummer,
          kunde.adressZusatzLieferung orElse kunde.adressZusatz,
          kunde.plzLieferung getOrElse kunde.plz,
          kunde.ortLieferung getOrElse kunde.ort,
          entity.abotypName,
          None,
          DateTime.now,
          personId,
          DateTime.now,
          personId
        )

        stammdatenWriteRepository.getById(tourlieferungMapping, entity.id) map { tourlieferung =>
          stammdatenWriteRepository.updateEntity[Tourlieferung, AboId](updated.copy(sort = tourlieferung.sort))
        } getOrElse {
          stammdatenWriteRepository.insertEntity[Tourlieferung, AboId](updated)
        }
      }
    }
  }

  def insertOrUpdateTourlieferungenByKunde(kunde: Kunde)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getTourlieferungenByKunde(kunde.id) map { tourlieferung =>
        stammdatenWriteRepository.updateEntity[Tourlieferung, AboId](tourlieferung.copy(
          kundeBezeichnung = kunde.bezeichnungLieferung getOrElse kunde.bezeichnung,
          strasse = kunde.strasseLieferung getOrElse kunde.strasse,
          hausNummer = kunde.hausNummerLieferung orElse kunde.hausNummer,
          adressZusatz = kunde.adressZusatzLieferung orElse kunde.adressZusatz,
          plz = kunde.plzLieferung getOrElse kunde.plz,
          ort = kunde.ortLieferung getOrElse kunde.ort
        ))
      }
    }
  }

  def handlePersonLoggedIn(personId: PersonId, timestamp: DateTime) = {
    DB autoCommit { implicit session =>
      stammdatenWriteRepository.getById(personMapping, personId) map { person =>
        implicit val pid = SystemEvents.SystemPersonId
        val updated = person.copy(letzteAnmeldung = Some(timestamp))
        stammdatenWriteRepository.updateEntity[Person, PersonId](updated)
      }
    }
  }

  def updateLieferplanungAbotypenListing(lieferplanungId: LieferplanungId)(implicit session: DBSession, personId: PersonId) = {
    stammdatenWriteRepository.getById(lieferplanungMapping, lieferplanungId) map { lp =>
      val lieferungen = stammdatenWriteRepository.getLieferungen(lieferplanungId)
      val abotypDates = (lieferungen.map(l => (dateFormat.print(l.datum), l.abotypBeschrieb))
        .groupBy(_._1).mapValues(_ map { _._2 }) map {
          case (datum, abotypBeschriebe) =>
            datum + ": " + abotypBeschriebe.mkString(", ")
        }).mkString("; ")
      val copy = lp.copy(abotypDepotTour = abotypDates)
      if (lp.abotypDepotTour != abotypDates) {
        stammdatenWriteRepository.updateEntity[Lieferplanung, LieferplanungId](copy)
      }
    }
  }

  def handleLieferplanungLieferungenChanged(lieferplanungId: LieferplanungId)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      updateLieferplanungAbotypenListing(lieferplanungId)
    }
  }

  def handleLieferplanungModified(entity: Lieferplanung, orig: Lieferplanung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      updateLieferplanungAbotypenListing(entity.id)
    }
  }

  def handleLieferungChanged(entity: Lieferung, orig: Lieferung)(implicit personId: PersonId) = {
    DB autoCommit { implicit session =>
      //Berechnung für erste Lieferung durchführen um sicher zu stellen, dass durchschnittspreis auf 0 gesetzt ist
      if (entity.anzahlLieferungen == 1) {
        recalculateLieferungOffen(entity, None)
      }
      val lieferungVorher = stammdatenWriteRepository.getGeplanteLieferungVorher(orig.vertriebId, entity.datum)
      stammdatenWriteRepository.getGeplanteLieferungNachher(orig.vertriebId, entity.datum) match {
        case Some(lieferungNach) => recalculateLieferungOffen(lieferungNach, Some(entity))
        case _ =>
      }
    }
  }

  def recalculateLieferungOffen(entity: Lieferung, lieferungVorher: Option[Lieferung])(implicit personId: PersonId, session: DBSession) = {
    val project = stammdatenWriteRepository.getProjekt
    val (newDurchschnittspreis, newAnzahlLieferungen) = lieferungVorher match {
      case Some(lieferung) if project.get.geschaftsjahr.isInSame(lieferung.datum.toLocalDate(), entity.datum.toLocalDate()) =>
        val sum = stammdatenWriteRepository.sumPreisTotalGeplanteLieferungenVorher(entity.vertriebId, entity.datum, project.get.geschaftsjahr.start(entity.datum.toLocalDate()).toDateTimeAtCurrentTime()).getOrElse(BigDecimal(0))

        val durchschnittspreisBisher: BigDecimal = lieferung.anzahlLieferungen match {
          case 0 => BigDecimal(0)
          case _ => sum / lieferung.anzahlLieferungen
        }

        val anzahlLieferungenNeu = lieferung.anzahlLieferungen + 1
        (durchschnittspreisBisher, anzahlLieferungenNeu)
      case _ =>
        (BigDecimal(0), 1)
    }

    val scaled = newDurchschnittspreis.setScale(2, HALF_UP)

    if (entity.durchschnittspreis != scaled || entity.anzahlLieferungen != newAnzahlLieferungen) {
      val updatedLieferung = entity.copy(
        durchschnittspreis = newDurchschnittspreis,
        anzahlLieferungen = newAnzahlLieferungen,
        modifidat = DateTime.now,
        modifikator = personId
      )
      stammdatenWriteRepository.updateEntity[Lieferung, LieferungId](
        updatedLieferung,
        lieferungMapping.column.durchschnittspreis,
        lieferungMapping.column.anzahlLieferungen,
        lieferungMapping.column.modifidat,
        lieferungMapping.column.modifikator
      )
    }
  }

  def modifyEntity[E <: BaseEntity[I], I <: BaseId](id: I, mod: E => E)(implicit session: DBSession, syntax: BaseEntitySQLSyntaxSupport[E], binder: SqlBinder[I], personId: PersonId): Option[E] = {
    modifyEntityWithRepository(stammdatenWriteRepository)(id, mod)
  }
}
