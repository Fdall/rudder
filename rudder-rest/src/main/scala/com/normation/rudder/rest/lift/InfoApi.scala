/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.rest.lift

import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.Endpoint
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import scala.language.implicitConversions
import com.normation.rudder.api.HttpAction
import com.normation.rudder.rest.ApiKind

/*
 * Information about the API
 */
class InfoApi(
    restExtractor    : RestExtractorService
  , supportedVersions: List[ApiVersion]
  , endpoints        : List[Endpoint]
) extends LiftApiModuleProvider {
  api =>

  import com.normation.rudder.rest.{ InfoApi => API }

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.ApiGeneralInformations => ApiGeneralInformations
        case API.ApiSubInformations     => ApiSubInformations
        case API.ApiInformations        => ApiInformations
    }).toList
  }


  final case class EndpointInfo(
      name    : String
    , action  : HttpAction
    , versions: Set[ApiVersion]
    , desc    : String
    , path    : ApiPath
   )

  private def list(startWith: Option[String]): JValue = {
    implicit def apiVersionToJValue(version: ApiVersion): JValue = {
      ("version" -> version.value) ~
      ("status" -> {if (version.deprecated) "deprecated" else "maintained"})
    }

    implicit class EndpointToJValue(endpoint: EndpointInfo) {
      def json: JValue = {
        val versions = endpoint.versions.map(_.value).toList.sorted.mkString("[",",","]")
        val action   = endpoint.action.name.toUpperCase()

        (endpoint.name -> endpoint.desc) ~
        (action        -> JString(versions + " /" + endpoint.path.value))
      }
    }

    val availableVersions = supportedVersions.toList.sortBy(_.value)

    availableVersions.reverse match {
      case Nil         =>
        ( "documentation" -> "http://www.rudder-project.org/rudder-api-doc/") ~
        ( "error" -> "No API version supported. please contact your administrator or report a bug")

      case max :: tail =>
        val list = endpoints.filter(e =>
          (e.schema.kind == ApiKind.General || e.schema.kind == ApiKind.Public) &&
          (startWith match {
            case None    => true
            case Some(x) => e.schema.path.parts.head.value == x
          })
        )

        val jsonEndpoints = list.groupBy( _.schema.name ).map { case (name, seq) =>
          //we just want to gather version for each api
          EndpointInfo(name, seq.head.schema.action, seq.map( _.version).toSet, seq.head.schema.description, seq.head.schema.path)
        }.toList.sortBy(_.path.value).map( _.json )

        ( "documentation" -> "http://www.rudder-project.org/rudder-api-doc/") ~
        ( "availableVersions" ->
          ( "latest" -> max.value) ~
          ( "all" -> availableVersions)
        ) ~
        ( "endpoints" -> jsonEndpoints )
    }
  }

  object ApiGeneralInformations extends LiftApiModule0 {
    val schema = API.ApiGeneralInformations
    val restExtractor = api.restExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val json = list(None)
      RestUtils.toJsonResponse(None, json)(schema.name, params.prettify)
    }
  }

  object ApiSubInformations extends LiftApiModule {
    val schema = API.ApiSubInformations
    val restExtractor = api.restExtractor
    def process(version: ApiVersion, path: ApiPath, name: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val json = list(Some(name))
      RestUtils.toJsonResponse(None, json)(schema.name, params.prettify)
    }
  }

  object ApiInformations extends LiftApiModule {
    val schema = API.ApiInformations
    val restExtractor = api.restExtractor
    def process(version: ApiVersion, path: ApiPath, name: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit def apiVersionToJValue(version: ApiVersion): JValue = {
        ("version" -> version.value) ~
        ("status" -> {if (version.deprecated) "deprecated" else "maintained"})
      }

      implicit class EndpointToJValue(endpoint: Endpoint) {
        def json: JValue = {
          val path = "/" + endpoint.prefix.value + "/" + endpoint.schema.path.value
          val action = endpoint.schema.action.name.toUpperCase()

          (action    -> path) ~
          ("version" -> endpoint.version)
        }
      }

      val json = endpoints.filter(e => e.schema.name.toLowerCase() == name.toLowerCase() && e.schema.kind != ApiKind.Internal).sortBy( _.version.value ).toList match {
        case Nil =>
          ( "documentation" -> "http://www.rudder-project.org/rudder-api-doc/") ~
          ( "error" -> s"No endpoint with name '${name}' defined.")

        case h :: tail =>
          val jsonEndpoints = (h::tail).map( _.json )

          ( "documentation" -> "http://www.rudder-project.org/rudder-api-doc/") ~
          ( name            -> h.schema.description ) ~
          ( "endpoints"     -> jsonEndpoints )
      }
      RestUtils.toJsonResponse(None, json)(schema.name, params.prettify)
    }
  }



}
