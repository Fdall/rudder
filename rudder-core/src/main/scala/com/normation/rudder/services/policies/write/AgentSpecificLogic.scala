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

package com.normation.rudder.services.policies.write

import org.apache.commons.io.FileUtils
import scala.io.Codec
import java.io.File
import net.liftweb.common.Box
import net.liftweb.util.Helpers.tryo
import com.normation.inventory.domain.AgentType
import com.normation.utils.Control.sequence
import net.liftweb.common.Full
import net.liftweb.common.Failure
import com.normation.cfclerk.domain.SystemVariable
import com.normation.cfclerk.domain.Variable

/*
 * This file contain agent-type specific logic used during the policy
 * writing process, mainly:
 * - specific files (like expected_reports.csv for CFEngine-based agent)
 * - specific format for "bundle" sequence.
 */

//containser for agent specific file written during policy generation
final case class AgentSpecificFile(
    path: String
)

//how do we write bundle sequence / input files system variable for the agent?
trait AgentFormatBundleVariables {
  import BuildBundleSequence._
  def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables
}


// does that implementation knows something about the current agent type
trait AgentSpecificGenerationHandle {
  def handle(agentType: AgentType): Boolean
}


// specific generic (i.e non bundle order linked) system variable
// todo - need to plug in systemvariablespecservice
// idem for the bundle seq

//write what must be written for the given configuration
trait WriteAgentSpecificFiles {
  def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]]
}


// the pipeline of processing for the specific writes
object WriteAllAgentSpecificFiles extends WriteAgentSpecificFiles {

  /**
   * Ordered list of handlers
   */
  var pipeline: List[AgentSpecificGenerationHandle with WriteAgentSpecificFiles with AgentFormatBundleVariables] =  {
    CFEngineAgentSpecificGeneration :: DscAgentSpecificGeneration :: Nil
  }

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    (sequence(pipeline) { handler =>
      if(handler.handle(cfg.agentType)) {
        handler.write(cfg)
      } else {
        Full(Nil)
      }
    }).map( _.flatten.toList)
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  def getBundleVariables(
      agentType   : AgentType
    , systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : Box[BundleSequenceVariables] = {
    //we only choose the first matching agent for that
    pipeline.find(handler => handler.handle(agentType)) match {
      case None    => Failure(s"We were unable to find how to create directive sequences for Agent type ${agentType.toString()}. " +
                            "Perhaps you are missing the corresponding plugin. If not, please report a bug")
      case Some(h) => Full(h.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles))
    }
  }

}


trait AgentSpecificGeneration extends AgentSpecificGenerationHandle with AgentFormatBundleVariables with WriteAgentSpecificFiles

object CFEngineAgentSpecificGeneration extends AgentSpecificGeneration {
  val GENEREATED_CSV_FILENAME = "rudder_expected_reports.csv"


  override def handle(agentType: AgentType): Boolean = agentType == AgentType.CfeCommunity || agentType == AgentType.CfeEnterprise

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    writeExpectedReportsCsv(cfg.paths, cfg.expectedReportsCsv, GENEREATED_CSV_FILENAME)
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  override def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables = CfengineBundleVariables.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles)


  private[this] def writeExpectedReportsCsv(paths: NodePromisesPaths, csv: ExpectedReportsCsv, csvFilename: String): Box[List[AgentSpecificFile]] = {
    val path = new File(paths.newFolder, csvFilename)
    for {
        _ <- tryo { FileUtils.writeStringToFile(path, csv.lines.mkString("\n"), Codec.UTF8.charSet) } ?~!
               s"Can not write the expected reports CSV file at path '${path.getAbsolutePath}'"
    } yield {
      AgentSpecificFile(path.getAbsolutePath) :: Nil
    }
  }
}

/*
 * This will go in the plugin, and will be contributed somehow at config time.
 */
object DscAgentSpecificGeneration extends AgentSpecificGeneration {

  override def handle(agentType: AgentType): Boolean = agentType == AgentType.Dsc

  override def write(cfg: AgentNodeWritableConfiguration): Box[List[AgentSpecificFile]] = {
    writeSystemVarJson(cfg.paths, cfg.systemVariables)
  }

  import BuildBundleSequence.{InputFile, TechniqueBundles, BundleSequenceVariables}
  override def getBundleVariables(
      systemInputs: List[InputFile]
    , sytemBundles: List[TechniqueBundles]
    , userInputs  : List[InputFile]
    , userBundles : List[TechniqueBundles]
  ) : BundleSequenceVariables = DscBundleVariables.getBundleVariables(systemInputs, sytemBundles, userInputs, userBundles)


  // just write an empty file for now
  private[this] def writeSystemVarJson(paths: NodePromisesPaths, variables: Map[String, Variable]) =  {
    val path = new File(paths.newFolder, "rudder.json")
    for {
        _ <- tryo { FileUtils.writeStringToFile(path, systemVariableToJson(variables) + "\n", Codec.UTF8.charSet) } ?~!
               s"Can not write json parameter file at path '${path.getAbsolutePath}'"
    } yield {
      AgentSpecificFile(path.getAbsolutePath) :: Nil
    }
  }

  private[this] def systemVariableToJson(vars: Map[String, Variable]): String = {
    //only keep system variables, sort them by name
    import net.liftweb.json._

    //remove these system vars (perhaps they should not even be there, in fact)
    val filterOut = Set(
        "SUB_NODES_ID"
      , "SUB_NODES_KEYHASH"
      , "SUB_NODES_NAME"
      , "SUB_NODES_SERVER"
      , "MANAGED_NODES_ADMIN"
      , "MANAGED_NODES_ID"
      , "MANAGED_NODES_IP"
      , "MANAGED_NODES_KEY"
      , "MANAGED_NODES_NAME"
      , "COMMUNITY", "NOVA"
      , "BUNDLELIST", "INPUTLIST"
    )

    val systemVars = vars.toList.sortBy( _._2.spec.name ).collect { case (_, v: SystemVariable) if(!filterOut.contains(v.spec.name)) =>
      // if the variable is multivalued, create an array, else just a String
      val value = if(v.spec.multivalued) {
        JArray(v.values.toList.map(JString))
      } else {
        JString(v.values.headOption.getOrElse(""))
      }
      JField(v.spec.name, value)
    }

    prettyRender(JObject(systemVars))
  }
}
