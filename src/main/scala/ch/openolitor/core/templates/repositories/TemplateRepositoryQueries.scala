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
package ch.openolitor.core.templates.repositories

import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._
import com.typesafe.scalalogging.LazyLogging

trait TemplateRepositoryQueries extends LazyLogging with TemplateDBMappings {
  lazy val mailTemplate = mailTemplateMapping.syntax("mailTemplate")
  lazy val sharedTemplate = sharedTemplateMapping.syntax("sharedTemplate")

  protected def getMailTemplatesQuery() = {
    withSQL {
      select
        .from(mailTemplateMapping as mailTemplate)
    }.map(mailTemplateMapping(mailTemplate)).list
  }

  protected def getMailTemplateByNameQuery(templateName: String) = {
    withSQL {
      select
        .from(mailTemplateMapping as mailTemplate)
        .where.eq(mailTemplate.templateName, parameter(templateName))
    }.map(mailTemplateMapping(mailTemplate)).single
  }

  protected def getSharedTemplateByNameQuery(templateName: String) = {
    withSQL {
      select
        .from(sharedTemplateMapping as sharedTemplate)
        .where.eq(sharedTemplate.templateName, parameter(templateName))
    }.map(sharedTemplateMapping(sharedTemplate)).single
  }
}