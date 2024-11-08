package i3d

import util.control.Breaks._
import scala.collection.mutable.ArrayBuffer
import scala.collection.immutable.SeqMap
import scala.math.{pow, sqrt, min, max, tan, toRadians}

import chisel3._

import chisel3.experimental.DataMirror
import chisel3.util.HasBlackBoxResource

import i3d.io._

// Assorted methods needed for bump map generation
object Utils {
  /**
   * IO flattening
   * @param bundle is a data bundle
   * @param dir is the desired data direction
   * @return a sequence of I3DCore objects
   */
  def flattenIOs(bundle: Bundle, dir: SpecifiedDirection) : Seq[I3DCore] = {
    def cloneDirection(d: Data) = DataMirror.specifiedDirectionOf(d) match {
      case SpecifiedDirection.Input => Input(UInt(1.W))
      case SpecifiedDirection.Output => Output(UInt(1.W))
      case _ => throw new Exception("Only Input/Output supported")
    }
    val flat = ArrayBuffer.empty[I3DCore]
    val orig = bundle.elements.filter(elt => DataMirror.specifiedDirectionOf(elt._2) == dir)
    for ((oname, odata) <- orig) {
      // Add single bit IOs as-is, otherwise break into individual bits
      // Need to deal with Vec and Seq types (recursively flatten with naming)
      def flattenVecSeq(name: String, d: Data): Seq[(String, Data)] = d match {
        case v: Vec[_] => v.zipWithIndex.flatMap{ case (elt, i) =>
          flattenVecSeq(s"${name}_${i}", elt)
        }
        case s: Seq[_] => s.zipWithIndex.flatMap{ case (elt, i) =>
          flattenVecSeq(s"${name}_${i}", elt.asInstanceOf[Data])
        }
        case _ => Seq((name, d))
      }
      flattenVecSeq(oname, odata).foreach{ case (name, data) =>
        val width = data.getWidth
        if (width > 1)
          for (i <- 0 until width) {  // asBools flattens to LSB -> MSB
            // TODO: support scrambling of bus bits for switching activity distribution
            // Bit indexing can't be done unless it is actually hardware.
            // Thus, we need to copy the direction and make it a 1-bit Data.
            flat += I3DCore(name, Some(i), cloneDirection(odata), None)
          }
        else
          flat += I3DCore(name, None, cloneDirection(odata), None)
      }
    }
    flat.toSeq
  }

  /**
    * Crude square packing in circle algorithm
    * Valid only for gridded (square) bump arrangement
    * Given particle size, signals per module, and any additional
    * required bumps within a circle given sig/PG rules:
    * @param gp: global params (I3DGlblParams)
    * @param sigs: number of signal bumps per module
    * @return A Tuple of (diameter, number of circles, pattern, number of signals)
    */
  def gridPacking(gp: I3DGlblParams, sigs: Int): (Double, Int, Seq[Int], Int) = {
    // This is a look-up table corresponding to the options for the maximal
    // signal pattern (row-by-row) inside of a circle
    val patternInCircle = Seq(
      Seq(1),
      Seq(2),
      Seq(2, 2),
      Seq(1, 3, 1),
      Seq(3, 3),  // transpose: (2, 2, 2)
      Seq(2, 4, 2),
      Seq(3, 3, 3),
      Seq(2, 4, 4, 2),
      Seq(1, 3, 5, 3, 1),
      Seq(2, 4, 4, 4, 2),  // transpose: (3, 5, 5, 3)
      Seq(3, 5, 5, 5, 3),
      Seq(2, 4, 6, 6, 4, 2),
      Seq(1, 5, 5, 7, 5, 5, 1),
      Seq(3, 5, 7, 7, 7, 5, 3)
    )
    val sigOpts = patternInCircle.map(_.sum)
    val diameters = patternInCircle.map { case p =>
      val dim1h = p.max  // longest horiz. dimension
      val dim2h = p.count(_ == dim1h)  // instances of longest horiz. dimension
      val diagh = sqrt(pow(dim1h, 2) + pow(dim2h, 2))  // Pythagorean theorem
      val dim1v = p.length  // longest vert. dimension
      val dim2v = p.head  // instances of longest vert. dimension
      val diagv = sqrt(pow(dim1v, 2) + pow(dim2v, 2))  // Pythagorean theorem
      max(diagh, diagv)
    }
    // Determine number of circles
    // TODO: Support number of circles other than 5 or 9 (module doesn't need to be square)
    // Target: 9, 8, 6, 5, and 4 (others are too awkwardly shaped)
    val circleOpts = Seq(8, 4)  // exclude redundant circle. Order matters.
    val sigsPerCircle = circleOpts.map(n => (sigs / n.toDouble).ceil.toInt)
    val sigsPerCircleIdx = sigsPerCircle.map(n => sigOpts.indexWhere(_ >= n))
    val diameterOpts = sigsPerCircleIdx.collect(diameters)
    // Choose the pattern with diameter closest to and larger than the particle size
    // TODO: take into account the signal to power/ground ratio
    val closestIdx = diameterOpts.indexWhere(_ >= gp.maxParticleSize)
    val finalCircles = circleOpts(closestIdx) + 1
    val finalSigsPerCircle = sigsPerCircle(closestIdx)
    val finalIdx = sigsPerCircleIdx(closestIdx)
    val finalDiameter = diameters(finalIdx)
    val finalPattern = patternInCircle(finalIdx)

    println(s"\tCircles per module: $finalCircles with diameter: $finalDiameter")
    println(s"\tSignals per circle: $finalSigsPerCircle in pattern: $finalPattern")
    (finalDiameter, finalCircles, finalPattern, finalSigsPerCircle)
  }

  /** Crude circle packing in square algorithm
    * Given parameters from gridPacking:
    * Calculate coordinates of clock, signal, power, and ground bumps contained within each module
    * The coords are Seq[(Int, Int)] with (x, y) coordinates of bumps
    * @param gp: global params (I3DGlblParams)
    * @param diam: diameter of circle
    * @param circles: number of circles
    * @param pattern: pattern of signal bumps in circle (row-by-row)
    * @param sigsPerCircle: number of signal bumps per circle (note: not necessarily sum of pattern)
    * @param sigsPerMod: number of signal bumps per module
    * @param isWide: true if circles should be arranged more horizontally
    * @return A Tuple of (signal coords, clock coords, power coords, ground coords)
    */
  def calcCoding(gp: I3DGlblParams, diam: Double, circles: Int, pattern: Seq[Int],
    sigsPerCircle: Int, sigsPerMod: Int, isWide: Boolean):
    (Seq[(Int, Int)], Seq[(Int, Int)], Seq[(Int, Int)], Seq[(Int, Int)]) = {
    // Generate the coordinate order in the pattern
    def patternCoords(pattern: Seq[Int], ringOnly: Boolean): Seq[(Int, Int)] = {
      pattern.zipWithIndex.flatMap{ case (rowWidth, y) =>
        val offset = (pattern.max - rowWidth) / 2
        if (ringOnly && y > 0 && y < pattern.length - 1)
          // Interior rows only return outer bumps
          Seq((offset, y), (rowWidth - offset - 1, y))
        else
          (0 until rowWidth).map(x => (x + offset, y))
      }
    }
    val coordsInCirle = patternCoords(pattern, false)
    val outerRing = patternCoords(pattern, true)

    val order = (gp.dataStatistic match {
      case "random" | "one-hot" => coordsInCirle  // sequential
      case "sequential" =>  // spiral
        val c = pattern.max  // circle width
        val r = pattern.length  // circle height
        val turns = (min(c, r) / 2.0).ceil.toInt  // number of turns
        (0 until turns).flatMap{ ti =>
          if (ti == r - 1 - ti && ti == c - 1 - ti)  // center
            Seq((ti, ti))
          else  // iterate clockwise over edges
            (ti until r - 1 - ti).map((ti, _)) ++
            (ti until c - 1 - ti).map((_, r - 1 - ti)) ++
            (ti + 1 until r - ti).reverse.map((c - 1 - ti, _)) ++
            (ti + 1 until c - ti).reverse.map((_, ti))
        }.filter(coordsInCirle.contains(_))  // only take coords in pattern
      case "normal" =>  // modified sawtooth - start point depends on pattern
        // TODO
        coordsInCirle
      case _ => throw new Exception("Invalid data statistic")
    }).take(sigsPerCircle)

    /* Next, pack circles into rectangle */

    // Determine offsets of circles (iterate clockwise)
    val offsets = circles match {
      case 4 =>  // 3 + 1 in corner
        val os = diam.ceil.toInt
        Seq((0, 0), (0, os), (os, os), (os, 0))
      case 5 =>  // 4 surrounding 1
        val mid = (diam / sqrt(2)).ceil.toInt
        Seq((0, 0), (0, 2*mid), (2*mid, 2*mid), (2*mid, 0), (mid, mid))
      case 6 =>  // 2 rows/3 cols, middle col staggered up. redundant is top-most.
        val hos = (3 / sqrt(13) * diam).ceil.toInt
        val vos1 = (sqrt(1 - 9 / 13) * diam).ceil.toInt // same row
        val vos2 = (6 / sqrt(13) - sqrt(1 - 9 / 13) * diam).ceil.toInt // row-to-row
        Seq((0, 0), (0, vos2), (hos, vos1+vos2), (2*hos, vos2), (2*hos, 0), (hos, vos1))
      case 8 =>  // everything offset by 15 degrees
        val os1 = ((sqrt(2) + sqrt(6)) / 2 * diam).ceil.toInt  // larger
        val os2 = ((sqrt(2) + sqrt(6)) / 2 * tan(toRadians(15)) * diam).ceil.toInt  // smaller
        Seq((0, 0), (os2, os1), (0, 2*os1), (os1, 2*os1-os2),
            (2*os1, 2*os1), (2*os1-os2, os1), (2*os1, 0), (os1, os2))
      case 9 =>  // 3 x 3
        val step = diam.ceil.toInt
        Seq((0, 0), (0, step), (0, 2*step),
            (step, 2*step), (2*step, 2*step), (2*step, step),
            (2*step, 0), (step, 0), (step, step))
      case _ => throw new Exception(s"Unsupported number of circles: ${circles}")
    }

    // Generate signal coordinates using ordering and offsets
    // Note: we must only take the correct number of signals per module
    // then concatenate with the redundant cluster (last in offsets)
    val sigCoords = (
      for ((x_os, y_os) <- offsets.init; (x, y) <- order)
        yield (x + x_os, y + y_os)
      ).take(sigsPerMod) ++ (
      for ((x, y) <- order)
        yield (x + offsets.last._1, y + offsets.last._2)
      )
    val cols = sigCoords.map(_._1).max + 1
    val rows = sigCoords.map(_._2).max + 1
    println(s"\tModule dimensions: $rows rows by $cols columns of bumps")

    // Generate power/ground coordinates
    // Rule: Immediately adjacent to a signal bump, use ground. Otherwise, use power.
    // In the unassigned (to signal) in each circle, use power
    // (Note in bumpMapGen that power/ground bumps are assigned first so this is OK)
    // TODO: check that sig to P/G distance is satisfied
    val pInCircles = for ((x_os, y_os) <- offsets; (x, y) <- coordsInCirle)
      yield (x + x_os, y + y_os)
    val gCoords = (for ((x_os, y_os) <- offsets; (x, y) <- outerRing)
      yield Seq((x - 1 + x_os, y + y_os),
                (x + 1 + x_os, y + y_os),
                (x + x_os, y - 1 + y_os),
                (x + x_os, y + 1 + y_os))
      ).flatten
      .filterNot{ case c => sigCoords.contains(c) || pInCircles.contains(c) }
      .filter{ case (x, y) => x >= 0 && x < cols && y >= 0 && y < rows }
    val pCoords = pInCircles ++ (
      for (r <- 0 until rows; c <- 0 until cols) yield (c, r)
      ).filterNot{ case c => sigCoords.contains(c) || gCoords.contains(c) }

    // Finally, calculate clock bump locations
    // Rule: Placed closest to center of module, but outside of circles
    // and at least a particle radius away from the center
    // TODO: this only works for a circle in the center (5 or 9 total)
    val clkCoordOpts = outerRing.flatMap { case (x, y) =>
      val x_os = offsets.last._1
      val y_os = offsets.last._2
      Seq((x + x_os - 1, y + y_os - 1),
          (x + x_os - 1, y + y_os),
          (x + x_os - 1, y + y_os + 1),
          (x + x_os, y + y_os - 1),
          (x + x_os, y + y_os + 1),
          (x + x_os + 1, y + y_os - 1),
          (x + x_os + 1, y + y_os),
          (x + x_os + 1, y + y_os + 1))
    }.filterNot{ case c => sigCoords.contains(c) || pInCircles.contains(c) || gCoords.contains(c) }
    .filter{ case (x, y) => x >= 0 && x < cols && y >= 0 && y < rows }
    val center = ((cols - 1) / 2.0, (rows - 1) / 2.0)
    val pclk = clkCoordOpts.filter{ case(x, y) =>
      sqrt(pow(center._1 - x, 2) + pow(center._2 - y, 2)) >= (gp.maxParticleSize + 1) / 2.0
    }.minBy{ case (x, y) =>
      sqrt(pow(center._1 - x, 2) + pow(center._2 - y, 2))
    }
    // Redundant clock is placed in the opposite corner relative to the center
    val rclk = ((center._1 + (center._1 - pclk._1)).toInt,
                (center._2 + (center._2 - pclk._2)).toInt)

    // Return
    (sigCoords, Seq(pclk, rclk), pCoords, gCoords)
  }

  /**
    * Calculate number of signal rows/columns, prioritizing the least number of bumps
    * and the ordering with fewer bumps facing pin edge
    * @param sigs: number of signal bumps
    * @param isWide: true if wide, false if tall
    * @return A tuple of (number of signal rows, number of signal columns, number of extra bumps)
    */
  def calcRowsCols(sigs: Int, isWide: Boolean): (Int, Int, Int) = {
    val rowsSigOpts = Seq(sqrt(sigs.toDouble).floor.toInt,
                          sqrt(sigs.toDouble).ceil.toInt)
    val colsSigOpts = rowsSigOpts.map(r => (sigs / r.toDouble).ceil.toInt)
    val (rowsSig, colsSig) = {
      if (rowsSigOpts(0) * colsSigOpts(0) < rowsSigOpts(1) * colsSigOpts(1))
        if (isWide) (colsSigOpts(0), rowsSigOpts(0))
        else (rowsSigOpts(0), colsSigOpts(0))
      else
        if (isWide) (rowsSigOpts(1), colsSigOpts(1))
        else (colsSigOpts(1), rowsSigOpts(1))
    }
    (rowsSig, colsSig, rowsSig * colsSig - sigs)
  }

  /**
   * Determine the indices of power/ground bumps in the relevant dimension
   * Step 1: Signal/power/ground pattern generation
   * Evenly distribute rowsSig and colsSig between power rows and ground columns
   * To do this, need to make a Seq filled with the quotient, and obtain the remainder
   * Then split the quotient Seq at the remainder position, and add 1 to the first half
   * Then perform binary recursion to evenly diffuse the smaller Seq into the larger
   * Step 2: Calculate row/column indices of power/ground bumps
   * At the edges, the signals virtually spill over into adjacent modules
   * So we must add sigsPerPG to the pattern gen then subtract half
   * @param sigs: number of signal bumps in this dimension
   * @param pg: number of power and ground bumps in this dimension
   * @param sigsPerPG: number of signal bumps between power/ground bumps in this dimension
   * @return A Seq[Int] with row indices of power bumps or col indices of ground bumps
   */
  def pgIdxGen(sigs: Int, pg: Int, sigsPerPG: Int): Seq[Int] = {
    val splitQuotient = Seq.fill(pg + 1)((sigs + sigsPerPG) / (pg + 1)).splitAt((sigs + sigsPerPG) % (pg + 1))
    def diffuse(l: Seq[Int], s: Seq[Int]): Seq[Int] = {
      val splitL = l.splitAt(l.length / 2)
      if (s.length == 0) l
      else if (s.length == 1) splitL._1 ++ s ++ splitL._2
      else {
        val splitS = s.tail.splitAt(s.tail.length / 2)
        diffuse(splitL._1 ++ s.take(1), splitS._1) ++ diffuse(splitL._2, splitS._2)
      }
    }
    val pattern = {
      if (splitQuotient._1.length >= splitQuotient._2.length)
        diffuse(splitQuotient._1.map(_ + 1), splitQuotient._2)
      else
        diffuse(splitQuotient._2, splitQuotient._1.map(_ + 1))
    }
    pattern.scanLeft(0)(_ + _ + 1)
           .map(_ - 1 - sigsPerPG/2)
           .drop(1).dropRight(1)
  }

  /**
    * Get coordinates of extra signal bumps to assign to ground
    * In a counter-clockwise circle, get the cornermost bumps, starting at (0, 0)
    * Then reorder based on which side the pin is on (prioritize farther ones).
    * This method reduces the radius of the clock tree in each module
    * while decreasing the length of routing to all IO cells.
    * For ranges: col pattern is (0), (0, 1), (0, 1, 2), (0, 1, 2, 3), etc.
    * and row is reverse (count down within each of those groups in col pattern).
    * We must also adjust for any rows/cols of power/ground.
    * Use recursion to generate this pattern and take as many entries as necessary.
    * @param extras: number of extra signal bumps
    * @param pinSide: side of pins
    * @param pRows: indices of power rows
    * @param gCols: indices of ground columns
    * @param rows: number of rows
    * @param cols: number of columns
    * @return A Seq[(Int, Int)] with coordinates of extra signal bumps
    */
  def extraCoordGen(extras: Int, pinSide: String, pRows: Seq[Int], gCols: Seq[Int],
                    rows: Int, cols: Int): Seq[(Int, Int)] = {
    val sideDropTake = pinSide match{
      case "N" => 0
      case "W" => 1
      case "S" => 2
      case "E" => 3
    }
    // Recursion
    (1 to (extras / 4.0).ceil.toInt).foldLeft(Seq.empty[(Int, Int)]){ case (coords, e) =>
      val range = Range(0, e)
      val nextCoords = (range zip range.reverse).map{ case(c, r) =>
        val cAdjUp = gCols.indexWhere(c >= _) + 1
        val cAdjDown = gCols.reverse.indexWhere(cols - c - 1 <= _) + 2
        val rAdjUp = pRows.indexWhere(r >= _) + 1
        val rAdjDown = pRows.reverse.indexWhere(rows - r - 1 <= _) + 2
        val ord = Seq((c + cAdjUp, r + rAdjUp),
                      (cols - c - cAdjDown, r + rAdjUp),
                      (cols - c - cAdjDown, rows - r - rAdjDown),
                      (c + cAdjUp, rows - r - rAdjDown))
        ord.drop(sideDropTake) ++ ord.take(sideDropTake)
      }.flatten
      coords ++ nextCoords
    }.take(extras)
  }

  /**
    * Perform bump map generation
    * @param tx: flattened TX IOs
    * @param rx: flattened RX IOs
    * @param numSigs: number of signal bumps per module (excl. redundant bumps)
    * @param mods: Tuple of module dimensions incl. redundant modules (rows, cols)
    * @param isWide: wide orientation
    * @param sCoords: (ordered) coordinates of signal bumps
    * @param cCoords: coordinates of clock bumps
    * @param pCoords: coordinates of power bumps
    * @param gCoords: coordinates of ground bumps
    * @param eCoords: coordinates of extra signal bumps
    * @return Tuple of (the final 2D bump map, final 1D bump map)
    */
  def bumpMapGen(tx: Seq[I3DCore], rx: Seq[I3DCore],
                 numSigs: Int, modDims: (Int, Int), isWide: Boolean,
                 sCoords: Seq[(Int, Int)], cCoords: Seq[(Int, Int)],
                 pCoords: Seq[(Int, Int)], gCoords: Seq[(Int, Int)],
                 eCoords: Seq[(Int, Int)]):
                  (Array[Array[I3DBump]], Seq[I3DBump]) = {
    // Determine number of (non-)redundant modules
    val mods = tx.length / numSigs
    val redMods = modDims._1 * modDims._2 - mods
    // Determine number of rows and columns per module from the maximum indices found in the coordinates
    val allCoords = sCoords ++ gCoords  // only these are needed
    val rows = allCoords.map(_._2).max + 1
    val cols = allCoords.map(_._1).max + 1
    // Initialize bump maps
    val txBumpMap, rxBumpMap = Array.ofDim[I3DBump](mods + redMods, rows, cols)
    var codedClk = false  // coded clock check
    // Counters
    var bitCnt = 0  // signal bit in module
    var redBitCnt = 0  // redundant bit in module
    // Loop through modules
    for (m <- 0 until (mods + redMods)) {
      // First, map power bumps
      for ((x, y) <- pCoords) {
        txBumpMap(m)(y)(x) = Pwr()
        rxBumpMap(m)(y)(x) = Pwr()
      }
      // Next, map ground bumps
      for ((x, y) <- (gCoords ++ eCoords)) {
        txBumpMap(m)(y)(x) = Gnd()
        rxBumpMap(m)(y)(x) = Gnd()
      }
      // Next, map clock bumps
      for ((x, y) <- cCoords) {
        txBumpMap(m)(y)(x) = TxClk(m, codedClk, m >= mods)
        rxBumpMap(m)(y)(x) = RxClk(m, codedClk, m >= mods)
        codedClk = true
      }
      // Finally, map signal bumps
      for (((x, y), i) <- sCoords.zipWithIndex) {
        if (m < mods) {  // non-redundant module
          if (i >= numSigs) {  // redundant bits in module
            txBumpMap(m)(y)(x) = TxSig(redBitCnt, m, None)
            rxBumpMap(m)(y)(x) = RxSig(redBitCnt, m, None)
          } else {  // signal bits in module
            txBumpMap(m)(y)(x) = TxSig(bitCnt, m, Some(tx(bitCnt).copy(relatedClk = Some(s"TXCKP$m"))))
            rxBumpMap(m)(y)(x) = RxSig(bitCnt, m, Some(rx(bitCnt).copy(relatedClk = Some(s"RXCKP$m"))))
          }
        } else {  // redundant module
          txBumpMap(m)(y)(x) = TxSig(bitCnt, m, None)
          rxBumpMap(m)(y)(x) = RxSig(bitCnt, m, None)
        }
        if (i >= numSigs) redBitCnt += 1  // increment redundant bit count
        else bitCnt += 1  // increment signal bit count
      }
      codedClk = false  // reset coded clock check for next module
      redBitCnt = (m + 1) * numSigs  // set redundant bit counter for next module
      if (m == mods - 1) bitCnt = 0  // reset bit counter for redundant modules
    }

    // Interleave bump maps then generate the correct ordering of module origins
    // Depending on pin side, the rows/cols of modules are different
    // Note modules in the longer dimension is doubled since Tx and Rx are separate
    val interleaved = Array(txBumpMap, rxBumpMap).transpose.flatten
    // Ordering of modules
    val totModRows = if (isWide) modDims._1 else modDims._1 * 2
    val totModCols = if (isWide) modDims._2 * 2 else modDims._2
    val modOrigins = {
      if (isWide)  // Tx left of Rx, col-by-col
        for (c <- 0 until totModCols by 2; r <- 0 until totModRows)
          yield Seq((r * rows, c * cols), (r * rows, (c + 1) * cols))
      else  // Tx under Rx, row-by-row
        for (r <- 0 until totModRows by 2; c <- 0 until totModCols)
          yield Seq((r * rows, c * cols), ((r + 1) * rows, c * cols))
    }.flatten
    require(modOrigins.length == interleaved.length,
      s"Error in Tx/Rx module to full module mapping: (${modOrigins.length} vs. ${interleaved.length})")

    // Flatten modules into final map
    // Module index tag is added to each bump for module redundancy mapping
    val finalMap = Array.ofDim[I3DBump](totModRows * rows, totModCols * cols)
    modOrigins.zipWithIndex.foreach { case ((r, c), m) =>
      for (mr <- 0 until rows; mc <- 0 until cols) {
        finalMap(r + mr)(c + mc) = interleaved(m)(mr)(mc)
        finalMap(r + mr)(c + mc).modCoord =
          I3DCoordinates[Int](
            x = c / cols,
            y = r / rows)
      }
    }

    // For the flat bump map, the ordering needs to go cluster-by-cluster
    // to aid in the coding redundancy module IO assignment.
    // Use the coordinates derived above for signal bumps
    // Get signal bumps first, then clock, power, ground, extras
    val flatMap: ArrayBuffer[I3DBump] = ArrayBuffer.empty
    val order = Seq(sCoords, cCoords, pCoords, gCoords, eCoords).flatten.distinct
    for ((r, c) <- modOrigins; (x, y) <- order)
      flatMap += finalMap(r + y)(c + x)

    // Return
    (finalMap, flatMap.toSeq)
  }

  /**
   * Calculate bump/iocell and pin locations
   * @param bumpMap: an Array containing the bump map
   * @param pinSide: side of pins
   * @param gp: global params
   * @param ip: instance params
   */
  def calcLocations(bumpMap: Array[Array[I3DBump]], pinSide: String,
                    gp: I3DGlblParams, ip: I3DInstParams) : Unit = {
    val rows = bumpMap.length
    val cols = bumpMap(0).length
    val isWide = Set("N", "S").contains(pinSide)

    // Calculate bump/iocell location
    // TODO: ignore tech grids and let the tools snap or use BigDecimal?
    for (r <- 0 until rows; c <- 0 until cols)
      // Update coordinates using calculated pitch and bumpOffset.
      bumpMap(r)(c).location = I3DCoordinates[Double](
        x = (c + 0.5) * gp.pitchH + (if (pinSide == "W") ip.bumpOffset else 0.0),
        y = (r + 0.5) * gp.pitchV + (if (pinSide == "S") ip.bumpOffset else 0.0)
      )

    // Calculate pin locations
    // Check for available routing resources
    // Assume 20% power assumption + 50% shielding/space penalty
    val routingTracks = ip.layerPitch.map{ case(layer, pitch) =>
      layer -> (gp.pitch / pitch * 1000).floor.toInt}
    val sumTracks = routingTracks.map(_._2).sum
    // Traverse row-by-row or column-by-column and get max number of signals
    val reqdSigs =
      if (Set("E", "W").contains(pinSide))
        (0 until cols).map{ c =>
          bumpMap.map(_(c)).filter(_.coreSig.isDefined).length}.max
      else
        (0 until rows).map{ r =>
          bumpMap(r).filter(_.coreSig.isDefined).length}.max
    require(sumTracks * 0.4 >= reqdSigs,
      s"""Not enough routing tracks on pin side (${pinSide}).
      |${reqdSigs} tracks requested but only ${sumTracks * 0.4} available.
      |""".stripMargin)
    // Query bumps with core signals in each row/col depending on pinSide
    val coreSigs = (if (isWide) bumpMap.transpose else bumpMap).map{
      _.collect{ case b if b.coreSig.isDefined => b.coreSig.get}}
    // Spread signals out evenly across all layers,
    // starting from the lowest layer in the middle of the row/column
    // Assume that tools will snap to track
    val pinOffsetsPos = (
      for ((layer, tracks) <- routingTracks; t <- 0 until tracks / 2)
        yield (t * ip.layerPitch(layer) / 1000, layer)
      ).grouped(sumTracks / reqdSigs).map(_.head).toSeq
    val pinOffsetsNeg = pinOffsetsPos.map{ case(os, lay) => (-os, lay)}
    val pinOffsets: Seq[(Double, String)] = pinSide match {  // interleaving
      case "N" | "E" =>  // reverse (farthest I/O appears first)
        Seq(pinOffsetsPos.reverse, pinOffsetsNeg.reverse).transpose.flatten
      case _ => Seq(pinOffsetsPos, pinOffsetsNeg).transpose.flatten.tail
    }
    coreSigs.zipWithIndex.foreach{ case (grp, i) =>
      (grp zip pinOffsets.take(grp.length)).foreach{ case (sig, (os, lay)) =>
        sig.pinLayer = lay
        sig.pinLocation = pinSide match {
          case "W" => I3DCoordinates[Double](
                        x = 0,
                        y = (i + 0.5) * gp.pitchV + os)
          case "E" => I3DCoordinates[Double](
                        x = cols * gp.pitchH + ip.bumpOffset,
                        y = (i + 0.5) * gp.pitchV + os)
          case "S" => I3DCoordinates[Double](
                        x = (i + 0.5) * gp.pitchH + os,
                        y = 0)
          case "N" => I3DCoordinates[Double](
                        x = (i + 0.5) * gp.pitchH + os,
                        y = rows * gp.pitchV + ip.bumpOffset)
        }
      }
    }
  }
}
