/*
 * o                        o     o   o         o
 * |             o          |     |\ /|         | /
 * |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 * |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 * O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *             |
 *          o--o
 * o--o              o               o--o       o    o
 * |   |             |               |    o     |    |
 * O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 * |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 * o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 * Logical Markov Random Fields.
 *
 * Copyright (C) 2012  Anastasios Skarlatidis.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lomrf.mln.grounding

import java.{util => jutil}

import akka.actor.{Actor, ActorRef}
import auxlib.log.Logging
import gnu.trove.list.array.TIntArrayList
import gnu.trove.map.TIntFloatMap
import gnu.trove.map.hash.{TIntFloatHashMap, TIntObjectHashMap}
import lomrf._

import scala.language.postfixOps
import scalaxy.streams.optimize


/**
 * @author Anastasios Skarlatidis
 */
private final class CliqueRegisterWorker(
                                          val index: Int,
                                          master: ActorRef,
                                          atomRegisters: Array[ActorRef],
                                          createDependencyMap: Boolean) extends Actor with Logging {

  private var hashCode2CliqueIDs = new TIntObjectHashMap[TIntArrayList]()
  private var cliques = new TIntObjectHashMap[CliqueEntry](DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY)


  /**
   * This structure is useful when Machine Learning is used, it represents the relations between FOL clauses and their
   * groundings. Specifically, the structure stores for each ground clause the it of the FOL clause that becomes, as
   * well as how many times the this ground class is generated by the same FOL clause (freq). In a nutshell, the
   * structure of the 'cliqueDependencyMap' is the following:
   * {{{
   *  Map [ groundClause[ID:Int] -> Map [Clause[ID:Int] -> Freq:Int]]
   * }}}
   *
   * Please note that when the 'Freq' number is negative, then we implicitly declare that the  weight of the
   * corresponding FOL 'Clause[ID:Int]' has been inverted during the grounding process.
   *
   */
  private var dependencyMap: DependencyMap = _

  private val numOfAtomBatches = atomRegisters.length

  private var cliqueID = 0



  override def preStart(): Unit = {
    if(createDependencyMap)
      dependencyMap = new TIntObjectHashMap[TIntFloatMap](DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY)
  }

  def receive = {
    case ce: CliqueEntry =>

      debug("CliqueRegister[" + index + "] received '" + ce +"' message.")
      if (ce.weight == 0 && ce.variables.length == 1)
        atomRegisters(ce.variables(0) % numOfAtomBatches) ! QueryVariable(ce.variables(0))
      else if (ce.weight != 0) storeClique(ce)

    case GRND_Completed =>
      debug(s"CliqueRegister[$index] received 'GRND_Completed' message.")
      debug(s"CliqueRegister[$index] collected total ${cliques.size} cliques.")

      sender ! NumberOfCliques(index, cliques.size())

    case StartID(offset: Int) =>
      debug("CliqueRegister[" + index + "] received 'StartID("+offset+")' message.")

      val collectedCliques =
        if(offset == 0) { //do not adjust clique ids
          // Register (atomID -> cliqueID) mappings
          val iterator = cliques.iterator()
          while (iterator.hasNext) {
            iterator.advance()
            registerAtoms(iterator.value().variables, iterator.key())
          }
          CollectedCliques(index, cliques, if(createDependencyMap) Some(dependencyMap) else None)
        }
        else {//adjust clique ids
          hashCode2CliqueIDs = null //not needed anymore (allow GC to delete it)

          val resultingCliques = new TIntObjectHashMap[CliqueEntry](cliques.size() + 1, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY)

          val resultingDependencyMap: Option[DependencyMap] =
            if(createDependencyMap)
              Some(new TIntObjectHashMap[TIntFloatMap](DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY))
            else None

          val iterator = cliques.iterator()
          var currentClique: CliqueEntry = null
          var finalID = NO_ENTRY_KEY
          var oldID = NO_ENTRY_KEY

          while (iterator.hasNext) {
            iterator.advance()

            oldID = iterator.key()
            finalID = oldID + offset
            currentClique = iterator.value()

            // Store clique mappings with the new 'final' id as key
            resultingCliques.put(finalID, currentClique)

            //if(createDependencyMap)
            resultingDependencyMap.foreach(_.put(finalID, dependencyMap.get(oldID)))

            // Register (atomID -> cliqueID) mappings
            registerAtoms(currentClique.variables, finalID)

          }

          // Not needed anymore (allow GC to delete it)
          cliques = null
          dependencyMap = null

          CollectedCliques(index, resultingCliques, resultingDependencyMap)
        }



      debug(s"CliqueRegister[$index] sending to master the CollectedCliques message, containing ${collectedCliques.cliques.size} cliques.")

      master ! collectedCliques

    case msg =>
      debug("CliqueRegister[" + index + "] received an unknown message '" + msg + "' from " + sender)
      error("CliqueRegister[" + index + "] received an unknown message.")
  }

  @inline private def registerAtoms(variables: Array[Int], cliqueID: Int): Unit ={
    // Register (atomID -> cliqueID) mappings
    for (variable <- variables; atomID = math.abs(variable))
      atomRegisters(atomID % numOfAtomBatches) ! Register(atomID, cliqueID)
  }


  private def storeClique(cliqueEntry: CliqueEntry) {
    //statReceived += 1

    @inline def fetchClique(fid: Int): CliqueEntry = cliques.get(fid)

    @inline def put(fid: Int, clique: CliqueEntry) = cliques.put(fid, clique)

    @inline def registerVariables(variables: Array[Int]): Unit = optimize {
      for (i <- 0 until variables.length) {
        val atomID = math.abs(variables(i))
        atomRegisters(atomID % numOfAtomBatches) ! atomID
      }
    }

    @inline def addToDependencyMap(cliqueID: Int, cliqueEntry: CliqueEntry): Unit = if(createDependencyMap){
      val clauseStats = new TIntFloatHashMap(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY, 0)
      clauseStats.put(cliqueEntry.clauseID, cliqueEntry.freq)
      dependencyMap.put(cliqueID, clauseStats)
    }

    val storedCliqueIDs = hashCode2CliqueIDs.get(cliqueEntry.hashKey)

    // (1) check for a stored clique with the same variables
    if (storedCliqueIDs ne null) {

      val iterator = storedCliqueIDs.iterator()
      var merged = false
      var storedId = -1
      while (iterator.hasNext && !merged) {
        storedId = iterator.next()
        val storedClique = fetchClique(storedId)

        if (jutil.Arrays.equals(storedClique.variables, cliqueEntry.variables)) {
          if (storedClique.weight != Double.PositiveInfinity) {
            // merge these cliques
            if (cliqueEntry.weight == Double.PositiveInfinity) {
              // When the stored constraint (from a previous run/iteration) is soft and
              // the new one is hard; then the resulting constraint will be hard.
              storedClique.weight = Double.PositiveInfinity
            }
            else {
              // When both stored and new constraints are soft, then merge these constraints
              storedClique.weight += cliqueEntry.weight
            }
          }
          // Otherwise, the stored constrain is hard, do not change anything and
          // thus ignore the current constraint.

          //state that a merging operation is performed.
          merged = true
          //statMerged += 1
        }
      } // while


      if (!merged) {
        // The constraint is not merged, thus we simply store it.
        put(cliqueID, cliqueEntry)
        storedCliqueIDs.add(cliqueID)
        registerVariables(cliqueEntry.variables)

        // add to dependencyMap:
        addToDependencyMap(cliqueID, cliqueEntry)

        // next cliqueID
        cliqueID += 1
      }
      else if(createDependencyMap){
        // Add or adjust the corresponding frequency in the dependencyMap
        dependencyMap.get(storedId).adjustOrPutValue(cliqueEntry.clauseID, cliqueEntry.freq, cliqueEntry.freq)
      }

    }
    else {
      // (2) Otherwise store this clique
      if (cliqueEntry.weight != 0) {
        put(cliqueID, cliqueEntry)
        val newEntries = new TIntArrayList()
        newEntries.add(cliqueID)
        hashCode2CliqueIDs.put(cliqueEntry.hashKey, newEntries)

        // add to dependencyMap:
        addToDependencyMap(cliqueID, cliqueEntry)

        // next cliqueID
        cliqueID += 1
      }
      registerVariables(cliqueEntry.variables)

    }
  } // store(...)

}
