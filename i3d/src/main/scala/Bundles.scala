package i3d

import scala.collection.immutable.SeqMap

import chisel3._

import chisel3.experimental.{Analog, DataMirror, AutoCloneType}
import chisel3.util.{log2Ceil, Cat}

import i3d.io._

/** Top-level and adapter bundles */
class BumpsBundle
  (implicit p: I3DParams) extends Record with AutoCloneType {
  // Filter out power and ground bumps
  val sigBumps: Seq[I3DBump] = p.flatBumpMap.filter(b => b match {
    case _:Pwr | _:Gnd => false
    case _ => true
  })
  // elements map is the bump name -> (cloned) ioType
  val elements: SeqMap[String, Data] = SeqMap(sigBumps.map(b => b.bumpName -> {
    if (b.coreSig.isDefined) b.coreSig.get.cloneIoType
    else (b match {
      case _:TxSig => Output(UInt(1.W))
      case _:RxSig => Input(UInt(1.W))
      case _:TxClk => Output(Clock())
      case _:RxClk => Input(Clock())
      case _ => throw new Exception("Should not get here")
  })}):_*)
	def apply(elt: String): Data = elements(elt)

  // Connect the correct bumps in this bundle to the corresponding module bundle
  def connectToModuleBundle(that: ModuleBundle): Unit = {
    that.elements foreach { elt => elt._2 <> elements(elt._1) }
    // TODO: For Chisel 3.6+: (that: Data).waiveAll <> (this: Data).waiveAll
  }

  // Get related clock for a bump
  def getRelatedClk(bumpName: String): Clock = {
    val bump = sigBumps.find(b => b.bumpName == bumpName).get
    bump match {
      case _:TxClk | _:RxClk =>
        if (bumpName.contains("CKR"))  // coded redundant clock
          elements(bumpName.replace("CKR", "CKP")).asUInt(0).asClock
        else
          elements(bumpName).asUInt(0).asClock
      case _ =>
        if (bump.coreSig.isDefined) {
          elements(bump.coreSig.get.relatedClk.get).asUInt(0).asClock
        } else {  // shifted redundant signal
          val modNum = bump.modCoord.linearIdx
          bump match {
            case _:TxSig => elements(s"TXCKP${modNum}").asUInt(0).asClock
            case _:RxSig => elements(s"RXCKP${modNum}").asUInt(0).asClock
            case _ => throw new Exception("Should not get here")
          }
        }
    }
  }

  // Return only the input clocks
  def inputClocks: Seq[Clock] = getElements.collect{case c: Clock => c}
    .filter(c => DataMirror.directionOf(c) == ActualDirection.Input)
}

class CoreBundle(implicit p: I3DParams) extends Record with AutoCloneType {
  // Filter out power and ground bumps, and coreSig must be defined
  val coreSigs: Seq[I3DBump] = p.flatBumpMap.filter(b => b match {
    case _:Pwr | _:Gnd => false
    case _ => b.coreSig.isDefined
  })
  // elements map is the coreSig fullName -> Flipped((cloned) ioType)
  val elements: SeqMap[String, Data] = SeqMap(coreSigs.map{c =>
    val sig = c.coreSig.get
    sig.fullName -> Flipped(sig.cloneIoType)
  }:_*)
	def apply(elt: String): Data = elements(elt)

  // Connect the correct core signals in this bundle to the corresponding module bundle
  def connectToModuleBundle(that: ModuleBundle): Unit = {
    if (that.modCoord.isRedundant) {  // redundant module, tie to 0
      that.getElements.foreach( d => d := 0.U.asTypeOf(d) )
    } else that.elements.foreach( elt => elt._2 <> elements(elt._1) )
  }

  // Get related clock for a core signal
  def getRelatedClk(coreSigName: String): Clock = {
    val sig = coreSigs.find(c => c.coreSig.get.fullName == coreSigName).get
    require(sig.coreSig.isDefined, s"Cannot get related clock for core signal ${coreSigName}")
    sig.coreSig.get.relatedClk.get.asUInt(0).asClock
  }

  // Return only the clocks as a Record for port creation at the top level
  // TODO: this uses a DataMirror internal API, subject to change/removal
  def clksRecord: Record = {
    val clks: SeqMap[String, Clock] = SeqMap(coreSigs.withFilter(c => c match {
      case _:TxClk | _:RxClk => true
      case _ => false
    }).map(c => c.coreSig.get.name ->
      DataMirror.internal.chiselTypeClone(elements(c.coreSig.get.name))):_*)
    new Record with AutoCloneType { val elements = clks }
  }

  // Return only the input clocks
  def inputClocks: Seq[Clock] = getElements.collect{case c: Clock => c}
    .filter(c => DataMirror.directionOf(c) == ActualDirection.Input)

  // Hook up to the data bundle at the top level
  def connectDataBundle(dataBundle: Bundle): Unit = {
    // Connection inwards is straightforward
    // Convert to seq of bools and connect each core bit to each data bit
    val dbIn = dataBundle.getElements.withFilter(
      DataMirror.directionOf(_) == ActualDirection.Input
    ).flatMap(_.asUInt.asBools)
    val eltIn = p.flatTxOrder.flatMap(elements.get(_))
    (eltIn zip dbIn).foreach{ case (c, p) => c := p }

    // Connection outwards is more complicated
    // Need to iterate through each element in the data bundle
    // Then concatenate the correct number of core bits together to assign
    val dbOut = dataBundle.getElements.filter(
      DataMirror.directionOf(_) == ActualDirection.Output
    )
    val eltOut = p.flatRxOrder.flatMap(elements.get(_))
    var i = 0
    dbOut.foreach{ c =>
      val w = c.getWidth
      c := VecInit(eltOut.slice(i, i+w)).asTypeOf(c)
      i += w
    }
  }
}

/** Module-specific bundle, used for redundancy */
class ModuleBundle(
  val modCoord: I3DCoordinates[Int], val coreFacing: Boolean)
  (implicit p: I3DParams) extends Record with AutoCloneType {

  // First, extract the bumps corresponding to this module index
  val modSigs: Seq[I3DBump] = p.flatBumpMap.filter(b => b match {
    case _:Pwr | _:Gnd => false
    case _ => b.modCoord == modCoord  // defined for all non-power/ground bumps
  })
  // Filter out redundant bumps in coding redundancy for core-facing bundle
  .filterNot(_.coreSig.isEmpty && coreFacing && !modCoord.isRedundant)

  // elements map is the bump/core signal name -> (cloned) ioType
  // If this bundle is core-facing, get the coreSig name
  // Else, get the bumpName
  val elements: SeqMap[String, Data] = SeqMap(modSigs.map(b => {
    if (modCoord.isRedundant ||  // redundant module, get from bumpName
      (!coreFacing && b.coreSig.isEmpty)) { // redundant coding bump
      // TODO: make this more elegant than a string search
      val dtype = if (b.bumpName.contains("CK")) Clock() else UInt(1.W)
      b.bumpName -> (
        if (coreFacing ^ b.bumpName.contains("TX")) Output(dtype)
        else Input(dtype))
    } else if (coreFacing) {  // core facing, get from coreSig name and flip
      b.coreSig.get.fullName -> Flipped(b.coreSig.get.cloneIoType)
    } else   // bump facing, get from bumpName
      b.bumpName -> b.coreSig.get.cloneIoType
  }):_*)
  def apply(elt: String): Data = elements(elt)

  // Get clocks
  def clocks: Seq[Clock] = getElements.collect{case c: Clock => c}
}

class DefaultDataBundle(width: Int) extends Bundle {
  val tx = Output(UInt(width.W))
  val rx = Input(UInt(width.W))
}

class ExampleArrayBundle(
  numLanes: Int, width: Int, interleaving: Int) extends Bundle {
  val adcOut = Output(Vec(numLanes, Vec(interleaving, UInt(width.W))))
  val dacIn = Input(Vec(numLanes, Vec(interleaving, UInt(width.W))))
}