package lomrf.inference

import java.io.{FileOutputStream, PrintStream}

import lomrf.logic.AtomSignature
import lomrf.mln.grounding.MRFBuilder
import lomrf.mln.inference.{MCSAT, MaxWalkSAT}
import lomrf.mln.model.MLN
import lomrf.util.Utilities.io._
import org.scalatest.{Matchers, FunSpec}
import scala.collection.immutable.HashMap
import scala.io.Source

/**
 * Specification test for MCSAT algorithm used for marginal inference.
 *
 * @author Anastasios Skarlatidis
 * @author Vagelis Michelioudakis
 */
final class MCSATSpecTest extends FunSpec with Matchers {

  private val sep = System.getProperty("file.separator")
  private val testFilesPath = System.getProperty("user.dir") + sep + "Examples" + sep + "data" + sep +
    "tests" + sep + "inference" + sep

  private val mlnFiles = findFiles(strToFile(testFilesPath), f => f.getName.contains(".mln"))
  private val dbFilesList = findFiles(strToFile(testFilesPath), f => f.getName.contains(".db"))
  private val goldenFilesList = findFiles(strToFile(testFilesPath), f => f.getName.contains(".mcsat.golden"))

  describe("Caviar diagonal newton test in path: '" + testFilesPath + "'") {

    for(weightType <- List("HI")) {
      for (fold <- 0 to 9) {
        val mlnFile = mlnFiles.filter(f => f.getAbsolutePath.contains("fold_" + fold) &&
          f.getAbsolutePath.contains(sep + weightType + sep))
        val dbFiles = dbFilesList.filter(f => f.getAbsolutePath.contains("fold_" + fold) &&
          f.getAbsolutePath.contains(sep + weightType + sep))
        val goldenFiles = goldenFilesList.filter(f => f.getAbsolutePath.contains("fold_" + fold) &&
          f.getAbsolutePath.contains(sep + weightType + sep))

        for(db <- dbFiles) {
          describe("MLN from file '" + mlnFile(0) + "' with evidence from file '" + db) {
            val mln = MLN(
              mlnFileName = mlnFile(0).getAbsolutePath,
              evidenceFileName = db.getAbsolutePath,
              queryAtoms = Set(AtomSignature("HoldsAt", 2)),
              cwa = Set(AtomSignature("Happens", 2), AtomSignature("Close", 4), AtomSignature("Next", 2),
                AtomSignature("OrientationMove", 3), AtomSignature("StartTime", 1)))

            info("Found " + mln.formulas.size + " formulas")
            info("Found " + mln.constants.size + " constant types")
            info("Found " + mln.schema.size + " predicate schemas")
            info("Found " + mln.functionSchema.size + " function schemas")

            it("should contain 25 formulas") {
              mln.formulas.size should be(25)
            }

            it("should constants 5 constants sets (domains)") {
              mln.constants.size should be(5)
            }

            it("should contain 6 predicate schemas") {
              mln.schema.size should be(6)
            }

            it("should contain 7 function schemas") {
              mln.functionSchema.size should be(7)
            }

            describe("Creating MRF from previous MLN") {

              info("Creating MRF...")
              val mrfBuilder = new MRFBuilder(mln)
              val mrf = mrfBuilder.buildNetwork

              info("Created " + mrf.numberOfAtoms + " ground atoms")
              info("Created " + mrf.numberOfConstraints + " ground clauses")

              describe("Running marginal inference using MCSAT") {

                val prefix = db.getName.split(".db")(0)
                val golden = goldenFiles.find(f => f.getName.contains(prefix)).get

                val resultsWriter = new PrintStream(
                  new FileOutputStream(
                    mlnFile(0).getParent.getAbsolutePath + sep + prefix + ".mcsat.result"), true)

                val solver = new MCSAT(mrf)
                solver.infer()
                solver.writeResults(resultsWriter)

                var results = HashMap[String, Double]()
                for (line <- Source.fromFile(mlnFile(0).getParent.getAbsolutePath + sep + prefix + ".mcsat.result").getLines()) {
                  val element = line.split(" ")
                  results += ((element(0), element(1).toDouble))
                }

                var standard = HashMap[String, Double]()
                for (line <- Source.fromFile(golden.getAbsolutePath).getLines()) {
                  val element = line.split(" ")
                  standard += ((element(0), element(1).toDouble))
                }

                var max = 0.0
                var total = 0.0
                for( (atom, p) <- results) {
                  val diff = math.abs(p - standard.get(atom).get)
                  total += diff
                  if(diff > max)
                    max = diff
                }
                info("Maximum error: " + max)
                info("Average error " + total/results.size.toDouble)

                it("should be less or equal than 0.1") {
                  assert(max <= 0.1)
                }

              }
            }
          }

        }

      }
    }
  }


}