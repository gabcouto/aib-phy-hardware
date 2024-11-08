I3D Parameters
=================

This document explains the user-defined parameter structure of I3D and explains what they are used for.

The I3D architecture depends on a large set of global/instance technology/design parameters that will be used to construct it.
They dictate how the bump map is created based on the spec discretization.

Both I3DGlblParams and I3DInstParams are combined into I3DParams for post-processing.

All parameter types are given and are required unless denoted as "Optional".

## Global Parameters (I3DGlblParams):

Technology parameters:

* rate (Double)
  * The data rate in GT/s
* pitch (Double)
  * The min. bond pitch in um
* pitchOvrdH (Double, Optional)
  * Overrides the bond pitch in the horizontal (width) dimension. Must be greater than min. pitch.
* pitchOvrdV (Double, Optional)
  * Overrides the bond pitch in the vertical (height) dimension. Must be greater than min. pitch
* maxNode (Double)
  * The largest/oldest tech node (in nm) in the 3D stack (or expected/compatible)
* maxParticleSize (Int)
  * Maximum particle size to be repaired, measured in span of bond pitches
* patternOvrd (String, Optional)
  * "square" or "hex" - overrides default bump pattern for bump technology (HBI = square, ubumps = hex). Default cutoff is at 9um pitch.
* sigsPerPGOvrdH (String, Optional)
  * Overrides the number of signals between P/G bumps in the horizontal (width) dimension (dictated by SI/PI)
* sigsPerPGOvrdV (String, Optional)
  * Overrides the number of signals between P/G bumps in the vertical (height) dimension (dictated by SI/PI)
* modSize (Int)
  * Maximum number of data bits (Tx/Rx, each) in a module. Dictated by timing requirements.

Design parameters:

* redundArch (Int)
  * Data (active) redundancy architecture
  * 0 = none, 1 = coding, 2 = shifting
* redundRatio (Int)
  * Denotes the redundancy ratio of signal bumps to redundant bumps (default: 4).
  * Only used for shifting redundancy.
* hasDBI (Boolean)
  * True if Tx IOs are to implement DBI. Only used with coding redundancy.
* deskewArch (Int)
  * De-skew architecture. To be implemented.
* pinSide (String)
  * One of "N", "S", "E", "W". Applied on the default (non-mirrored/rotated) orientation.
* dataBundle (Bundle)
  * A Chisel Bundle object of all the IOs in the leader.
  * Each element must have a Direction (Input, Output) specified.
  * IMPORTANT: The ordering of the elements in the Bundle are used for bump assignment.
* dataStatistic (String)
  * The average statistics of the buses in the data bundle.
  * One of "random", "correlated", "one-hot", or "normal".
  * Used for pin-to-bump assignment in all redundancy modes and low-power coding in coding redundancy mode.


## Instance Parameters (I3DInstParams):

Technology parameters:

* node (Double)
  * The tech node (in nm) of the instance
* layerPitch: Map(String -> Double)
  * Map(layer name -> pitch in nm) corresponding to the track pitch on a set of layers desired for routing. The entries should be ordered from lowest to highest layer. Ensure that these layers are preferred for the pinSide parameter.
* viaKOZRatio (Double)
  * Ratio of the size of the keep-out zone around signal/power via stacks to the min. bond pitch. Superceded by tsvKOZRatio and bprKOZRatio if specified.
* bprKOZRatio: (Double, Optional)
  * Ratio of the size of the backside power TSV landing to the min. bond pitch. Superceded by tsvKOZRatio if specified.
* tsvKOZRatio: (Double, Optional)
  * Ratio of the size of the TSV keep-out zone to the min. bond pitch
* faceToStack (Boolean)
  * If this instance is a follower, denotes if the face side (FEOL) is facing the leader die.
  * If this instance is a leader, denotes if the face side (FEOL) is facing the follower die(s).
* powerF (Boolean)
  * True if power/ground are supplied from/thru the frontside.
* powerB (Boolean)
  * True if power/ground are supplied from/thru the backside.
* vNom (Double) 
  * The nominal supply voltage
* psmm (Double) 
  * The estimated delay (in ps) per distance (in mm) for paths in the nominal corner

Design parameters:

* isLeader (Boolean)
  * True if this instance is a leader, else is a follower
* orientation (String, Optional)
  * "MX" or "MY" only supported, if specified. Exported as a floorplan constraint but does not affect bump map calculation.
* pinSide (String, Optional)
  * Indicates pin side of the instance, although actual module configuration is determined by the global pinSide parameter.
  * One of "N", "S", "E", "W". Applied on the default (non-mirrored/rotated) orientation.
* bumpOffset (Double)
  * Denotes the offset of the bump array from the pin edge in um. Applied on the default (non-mirrored/rotated) orientation.
* baseAddress (BigInt)
  * Address of the CSR bus
* testProtocol (String)
  * Supported test protocol. Default = "IEEE1838" (not yet implemented)
* blackBoxModels: (Boolean)
  * If true, use blackbox generic behavioral models of analog blocks
* bumpCellName: (String, Optional)
  * Name of the bump cell used by the technology. May be required for collateral generator.
* ioCellName: (String, Optional)
  * Name of the IO cell used by the technology. May be required for collateral generator.
* bumpLoadFunc (Function: Double => Double)
  * User-specified function for calculating the load capacitance on bumps, and overrides the default internal calculation if specified.
  * To be implemented.
