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

package com.normation.rudder.rest

import com.normation.rudder.api.AclPath
import com.normation.rudder.api.AclPathSegment
import com.normation.rudder.api.HttpAction
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.api.{ ApiAuthorization => ApiAuthz }
import com.normation.rudder.service.user.UserService
import com.normation.eventlog.EventActor
import cats.implicits._
import cats.data._


/*
 * This trait allows to check for autorisation on a given boundedendpoint
 * for a given ApiToken.
 * T: authorization token type
 */
trait ApiAuthorization[T] {
  // given an user, check the authorization rights on the given endpoint/version
  // we also give the actual path to resolve resource binding (typically when an user
  // has only access to one directive, rule, setting...)
  // In the future, we will most likelly need to give the full REQ to assess
  // PUT/POST param validity.
  def checkAuthz(endpoint: Endpoint, requestPath: ApiPath): Either[ApiError, T]
}


/*
 * A simple class used by API and which hold authentication actor.
 * We only need the authz token/user name
 */
final case class AuthzToken(actor: EventActor)


/*
 * This service allows to know if the ACL module is set
 */
trait ApiAuthorizationLevelService {
  def aclEnabled: Boolean
  def name: String
}

// and default implementation is: no
class DefaultApiAuthorizationLevel(logger: Log) extends ApiAuthorizationLevelService {
  private[this] var level: Option[ApiAuthorizationLevelService] = None
  def overrideLevel(l: ApiAuthorizationLevelService): Unit = {
    logger.info(s"Update API authorization level to '${l.name}'")
    level = Some(l)
  }
  override def aclEnabled: Boolean = level.map( _.aclEnabled ).getOrElse(false)

  override def name: String = "Default implementation (RO/RW authorization)"
}


/*
 * Default authenticatio scheme. It will check for ACL enabled by the plugin.
 * In that implementation, we match ACL for token which are of "acl" kind.
 */
class AclApiAuthorization(logger: Log, userService: UserService, aclEnabled: () => Boolean) extends ApiAuthorization[AuthzToken] {

  def checkAuthz(endpoint: Endpoint, requestPath: ApiPath): Either[ApiError, AuthzToken] = {
    def checkRO(action: HttpAction): Option[String] = {
      if(action == HttpAction.GET || action == HttpAction.HEAD) Some("ok")
      else None
    }

    val user = userService.getCurrentUser
    for {
      // we want to compare the exact path asked by the user to take care of cases where he only has
      // access to a limited subset of named resourced for the endpoint.
      path <- requestPath.drop(endpoint.prefix).leftMap(msg => ApiError.BadRequest(msg, endpoint.schema.name))
      ok   <- (user.getApiAutz match {
                case ApiAuthz.None     =>
                  logger.debug(s"User '${user.actor.name}' does not have any authorizations.")
                  None
                case ApiAuthz.RW       =>
                  logger.debug(s"User '${user.actor.name}' has RW authorizations.")
                  Some("ok")
                // if ACL are not enable, we fall back to RO
                case ApiAuthz.RO       =>
                  logger.debug(s"User '${user.actor.name}' has RO authorization.")
                  checkRO(endpoint.schema.action)

                case ApiAuthz.ACL(_) if(!aclEnabled()) =>
                  logger.debug(s"User '${user.actor.name}' has ACL authorization but no plugin allows to interpret them. Revert to RO rights.")
                  checkRO(endpoint.schema.action)
                case ApiAuthz.ACL(acl) =>
                  logger.debug(s"User '${user.actor.name}' has ACL authorization and a plugin allows to interpret them.")
                  if(AclCheck(acl, path, endpoint.schema.action)) {
                    Some("ok")
                  } else {
                    None
                  }
              }).map(_ => Right(AuthzToken(EventActor(user.actor.name)))).getOrElse(
                Left(ApiError.Authz(s"User '${user.actor.name}' is not allowed to access ${endpoint.schema.action.name.toUpperCase()} ${endpoint.prefix.value + "/" + endpoint.schema.path.value}", endpoint.schema.name))
              )
    } yield {
      ok
    }
  }
}


/*
 * A simple object that gives the canonical API authorization
 * from an endpoint schema
 */
object AuthzForApi {

  def apply(api: EndpointSchema): ApiAclElement = {
    val aclPathSegments = api.path.parts.map { p => p match {
      case ApiPathSegment.Resource(v) => AclPathSegment.Wildcard
      case ApiPathSegment.Segment (v) => AclPathSegment.Segment(v)
    } }
    ApiAclElement(AclPath.FullPath(aclPathSegments), Set(api.action))
  }

  def withValues(api: EndpointSchema, values: List[AclPathSegment]): ApiAclElement = {
    def recReplace(api: List[ApiPathSegment], values: List[AclPathSegment]): List[AclPathSegment] = {
      api match {
        case Nil => Nil
        case ApiPathSegment.Segment(v)  :: t => AclPathSegment.Segment(v) :: recReplace(t, values)
        case ApiPathSegment.Resource(v) :: t => //if we have a replacement value, use it
          values match {
            case Nil     => AclPathSegment.Wildcard :: recReplace(t, Nil)
            case v :: vv => v :: recReplace(t, vv)
          }
      }
    }
    // fromListUnsafe is ok as we had a non empty list as source
    ApiAclElement(AclPath.FullPath(NonEmptyList.fromListUnsafe(recReplace(api.path.parts.toList, values))), Set(api.action))
  }
}


/*
 * Actual logic to check if a path is accepted by an ACL
 */
object AclCheck {

  /*
   * Given an Access Control List, check if the (path, action) is authorized.
   * Only the PATH is used to find the corresponding access control, so
   * ACL order matters (only in the case where DoubleWildcard ("**") are
   * present). For example, in the following cases, result will be different:
   *
   * acl = [
   *   "a / **", GET
   *   "a / b" , (GET, PUT)
   * ]
   * => AclCheck(acl, "a"  , GET) => true
   * => AclCheck(acl, "a/b", GET) => true
   * => AclCheck(acl, "a/b", PUT) => false
   *
   *
   * acl = [
   *   "a / b" , PUT
   *   "a / **", GET
   * ]
   * => AclCheck(acl, "a"  , GET) => true
   * => AclCheck(acl, "a/b", GET) => true
   * => AclCheck(acl, "a/b", PUT) => true
   */
  def apply(acl: List[ApiAclElement], path: ApiPath, action: HttpAction): Boolean = {
    // we look for the FIRS ACL whose path matches
    acl.find(x => matches( x.path, path ) ) match {
      case None                            => false
      case Some(ApiAclElement(_, actions)) => actions.contains(action)
    }
  }


  /*
   * Check if an ACL path matches the provided URL path
   */
  def matches(aclPath: AclPath, path: ApiPath): Boolean = {
    def recMatches(p1: List[AclPathSegment], p2: List[ApiPathSegment]): Boolean = {
      import AclPathSegment.{DoubleWildcard, Wildcard, Segment => AclSegment}
      import ApiPathSegment.{Segment => ApiSegment}

      (p1, p2) match {
        case ( Nil                  , Nil                  ) => true
        // "**" must be in last position. We don't make it matches anything else
        case ( DoubleWildcard :: Nil, _                    ) => true
        case ( Wildcard       :: t1 , _ :: t2              ) => recMatches(t1, t2)
        case ( AclSegment(n1) :: t1 , ApiSegment(n2) :: t2 ) => n1 == n2 && recMatches(t1, t2)
        case _                                               => false
      }
    }
    recMatches(aclPath.parts.toList, path.parts.toList)
  }

}



