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

import spray.routing._
import spray.http._
import spray.http.MediaTypes._
import spray.httpx.marshalling.ToResponseMarshallable._
import spray.httpx.SprayJsonSupport._
import spray.routing.Directive.pimpApply
import spray.json._
import spray.json.DefaultJsonProtocol._
import ch.openolitor.core._
import ch.openolitor.core.domain._
import ch.openolitor.core.db._
import spray.httpx.unmarshalling.Unmarshaller
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util._
import java.util.UUID
import akka.pattern.ask
import scala.concurrent.duration._
import akka.util.Timeout
import ch.openolitor.stammdaten.models._
import ch.openolitor.core.models.UserId

trait StammdatenRoutes extends HttpService with ActorReferences with AsyncConnectionPoolContextAware with SprayDeserializers {
  self: StammdatenRepositoryComponent =>

  implicit val abotypIdParamConverter = string2BaseIdConverter[AbotypId](AbotypId.apply)
  implicit val abotypIdPath = string2BaseIdPathMatcher[AbotypId](AbotypId.apply)
  implicit val personIdPath = string2BaseIdPathMatcher[PersonId](PersonId.apply)

  import StammdatenJsonProtocol._
  import EntityStore._

  implicit val timeout = Timeout(5.seconds)

  //TODO: get real userid from login
  def userId: UserId = Boot.systemUserId

  lazy val stammdatenRoute = aboTypenRoute ~ personenRoute

  lazy val personenRoute =
    path("personen") {
      get {
        //fetch list of personen
        onSuccess(readRepository.getPersonen) { personen =>
          complete(personen)
        }
      } ~
        post {
          complete("")
        }
    } ~
      path("personen" / personIdPath) { id =>
        get {
          //get detail of abotyp
          complete("")
        } ~
          (put | post) {
            complete("")
          } ~
          delete {
            complete("")
          }
      }

  lazy val aboTypenRoute =
    path("abotypen") {
      get {
        //fetch list of abotypen
        onSuccess(readRepository.getAbotypen) { abotypen =>
          complete(abotypen)
        }
      } ~
        post {
          entity(as[AbotypCreate]) { abotyp =>
            //create abotyp
            onSuccess(entityStore ? EntityStore.InsertEntityCommand(userId, abotyp)) {
              case event: EntityInsertedEvent =>
                //load entity
                onSuccess(readRepository.getAbotypDetail(AbotypId(event.id))) { abotyp =>
                  complete(abotyp)
                }
              case x =>
                complete(StatusCodes.BadRequest, s"No id generated:$x")
            }
          }
        }
    } ~
      path("abotypen" / abotypIdPath) { id =>
        get {
          //get detail of abotyp
          onSuccess(readRepository.getAbotypDetail(id)) { abotyp =>
            abotyp.map(a => complete(a)).getOrElse(complete(StatusCodes.NotFound))
          }
        } ~
          (put | post) {
            entity(as[AbotypUpdate]) { abotyp =>
              //update abotyp
              onSuccess(entityStore ? EntityStore.UpdateEntityCommand(userId, id, abotyp)) { result =>
                //refetch entity
                onSuccess(readRepository.getAbotypDetail(id)) { abotyp =>
                  complete(abotyp)
                }
              }
            }
          } ~
          delete {
            onSuccess(entityStore ? EntityStore.DeleteEntityCommand(userId, id)) { result =>
              complete("")
            }
          }
      }
}