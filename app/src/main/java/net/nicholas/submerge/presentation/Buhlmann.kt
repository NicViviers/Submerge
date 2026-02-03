package net.nicholas.submerge.presentation

import android.util.Log
import androidx.compose.runtime.MutableState
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

class Buhlmann(
    // Variables
    val depth_m: MutableState<Double>, // Depth in meters
    val time: MutableState<Int>, // Time in minutes
    val fO2: MutableState<Double>, // Fraction of oxygen TODO: Implement gas switching - shouldn't be too hard?
    val pFactor: MutableState<Int>, // TODO: Need to properly implement this for deco schedule,
                                    // maybe just assume a faster on-gassing rate or add additional nitrogen to tissue loading calculations?

    // Panel information
    val n2: MutableState<Double>,
    val noFly: MutableState<Int>,
    val noDeco: MutableState<Int>,
    val deco: MutableState<Int>,
) {
    private val depth = (depth_m.value - 1) / 10.0
    private val fN2 = 1.0 - fO2.value

    // Safety variables
    private val safetyStopDuration = 3 // Safety stop duration in minutes at 3 meters

    // Ascent / Descent rates
    private val ascentRate = 10 // Ascent rate in meters per minute
    private val ascentStepTime = 0.1 // Refresh tissues every 6 seconds
    private val descentRate = 10 // Descent rate in meters per minute
    private val descentStepTime = 0.1 // Refresh tissues every 6 seconds

    // Initiate tissue loading for atmospheric pressure
    private var tissues = (0..15).map { compLoading(fN2, fN2, 100.0, hN2[it]) }
    private var tissuesCopy = tissues // Copy for repeating no-deco simulations
    private var noFlyTissues = tissues // Copy for no-fly calculations

    private fun calculateCeilings(): List<Pair<Int, Double>> {
        return tissues.mapIndexed { i, it ->
            Pair(i, compCeiling(it, aN2[i], bN2[i]))
        }
    }

    // Finds the nearest set deco stop to the tissue ceiling depth in meters
    private fun nearestDecoStop(depth: Double): Int {
        return listOf(3, 6, 9, 12).filter { it >= depth }.minOrNull() ?: 12
    }

    private fun calculateDecompression(): List<Int> {
        val stops = mutableListOf(safetyStopDuration, 0, 0, 0) // 3m, 6m, 9m, 12m
        var lastStop = depth_m.value // Start ascent simulation from bottom depth

        var ceilings = calculateCeilings()
        var decoms = ceilings.filter { it.second > 1.0 }

        while (decoms.isNotEmpty()) {
            // Figure out which tissue needs to decompress at what depth
            val ceiling = decoms.maxOf { it.second }
            val nearestDeco = nearestDecoStop((ceiling - 1) * 10)

            // Calculate ascent time and loading
            val initialDepth = lastStop
            val ascent = lastStop - nearestDeco
            val stepDistance = ascentRate * ascentStepTime

            var currentDepth = initialDepth
            val totalSteps = (ascent / stepDistance).toInt()

            for (i in 1 .. totalSteps) {
                currentDepth -= stepDistance // Decrease depth by step distance

                // Refresh tissues while ascending
                tissues = tissues.mapIndexed { j, it ->
                    compLoading(it * fN2, ((currentDepth * 10) - 1) * fN2, ascentStepTime, hN2[j])
                }
            }


            // Re-calculate tissue loading after one minute of decompressing at nearest decompression depth
            tissues = tissues.mapIndexed { i, it ->
                compLoading(it * fN2, (1 + nearestDeco / 10) * fN2, 1.0, hN2[i])
            }

            // Keep track of the minutes decompressed
            stops[nearestDeco / 3 - 1] += 1
            lastStop = nearestDeco.toDouble()

            // Recheck ceilings for further decompression
            ceilings = calculateCeilings()
            decoms = ceilings.filter { it.second > 1.0 }
        }

        // Store tissue state for no-fly calculations after deco
        // TODO: Doesn't work?
        noFlyTissues = tissues

        return stops
    }

    private fun simulateDescent() {
        val stepDistance = descentRate * descentStepTime // Distance traveled per step
        var currentDepth = 0.0 // Starting depth
        val totalDescent = depth_m.value // Depth to descend to
        val totalSteps = (totalDescent / stepDistance).toInt()

        for (i in 1 .. totalSteps) {
            currentDepth += stepDistance

            // Refresh tissues while descending
            tissues = tissues.mapIndexed { j, it ->
                compLoading(it * fN2, ((currentDepth * 10) - 1) * fN2, descentStepTime, hN2[j])
            }
        }

        // Load tissues for bottom time
        tissues = tissues.mapIndexed { i, it ->
            compLoading(it * fN2, depth * fN2, time.value.toDouble(), hN2[i])
        }
    }

    // TODO: Something is messing with the tissue compartments when this gets called by the UI
    // It initially shows fine, but after switching to the others even back to the original then it nearly triples
    // Possibly the Deco / No Deco?
    fun calculateNoFly() {
        var maxTime = 0.0
        var slowestCompartmentIndex = -1

        for (i in noFlyTissues.indices) {
            val P0 = noFlyTissues[i]
            val T_half = hN2[i]

            if (P0 > 0.75) {
                val timeToFly = -T_half * ln(0.75 / P0)

                if (timeToFly > maxTime) {
                    maxTime = timeToFly
                    slowestCompartmentIndex = i
                }
            }
        }

        // Add safety margin to time (1 = 10%, 2 = 20%)
        Log.d("Submerge", "P0: ${(maxTime * (1 + 0 / 10)).toInt()}\n" +
                "P1: ${(maxTime * (1 + 1 / 10)).toInt()}\n" +
                "P2: ${(maxTime * (1 + 2 / 10)).toInt()}")
        Log.d("Submerge", "P0: ${ceil(maxTime * (1 + 0 / 10)).toInt()}\n" +
                "P1: ${ceil(maxTime * (1 + 1 / 10)).toInt()}\n" +
                "P2: ${ceil(maxTime * (1 + 2 / 10)).toInt()}")
//        val noFlyT = if (slowestCompartmentIndex != 0) ceil(maxTime * (1 + pFactor.value / 10)).toInt() else 0
        val noFlyT = if (slowestCompartmentIndex != 0) maxTime.toInt() else 0
        noFly.value = if (noFlyT > 99) 99 else noFlyT // Update no-fly state
    }

    fun calculateAll() {
        simulateDescent()
        tissuesCopy = tissues
        calculateNoFly()
        val decoT = calculateDecompression().sum()

        tissues = tissuesCopy // Prepare tissues for NDL simulations at depth after descent again

        // TODO: Rewrite this using HTs and ceilings instead of running tons of simulations until we get it
        // Run calculations for no deco limits
        val noDecoT = if (decoT == safetyStopDuration) {
            val surfaceTissues = (0..15).map { compLoading(fN2, fN2, 100.0, hN2[it]) }
            tissues = surfaceTissues // Reset tissues for new simulations
            var noDecoT = 0

            val timeCopy = time.value // Store user defined time so we can adjust it for no deco simulations
            time.value += 1

            while (noDecoT < 99 && calculateDecompression().sum() == safetyStopDuration) {
                time.value += 1
                noDecoT += 1
                tissues = surfaceTissues
            }

            time.value = timeCopy
            noDecoT -= time.value // Only show no deco after bottom time

            noDecoT
        } else { 0 }

        // Update Deco and No Deco states
        deco.value = if (decoT - safetyStopDuration > 99) 99 else decoT - safetyStopDuration
        noDeco.value = if (noDecoT > 99) 99 else noDecoT
    }

    companion object {
        // https://en.wikipedia.org/wiki/B%C3%BChlmann_decompression_algorithm#Versions
        // Tissue half-times
        val hN2 = listOf(
            5.0, 8.0, 12.5, 18.5,
            27.0, 38.3, 54.3, 77.0,
            109.0, 146.0, 187.0, 239.0,
            305.0, 390.0, 498.0, 635.0
        )

        // Pre-calculated to save resources
        // a = 2 * (tht ** -1/3)
        val aN2 = listOf(
            1.1696, 1.0, 0.8618, 0.7562,
            0.62, 0.5043, 0.441, 0.4,
            0.375, 0.35, 0.3295, 0.3065,
            0.2835, 0.261, 0.248, 0.2327
        )

        // Pre-calculated to save resources
        // b = 1.005 - (tht ** -1/2)
        val bN2 = listOf(
            0.5578, 0.6514, 0.7222, 0.7825,
            0.8126, 0.8434, 0.8693, 0.8910,
            0.9092, 0.9222, 0.9319, 0.9403,
            0.9477, 0.9544, 0.9602, 0.9653
        )

        // This tells us the loading (bar) of a tissue compartment at a depth (bar) for a given time (minutes)
        fun compLoading(Pbegin: Double, Pgas: Double, te: Double, tht: Double): Double {
            /**
             *  @param Pbegin: Inert gas pressure in compartment before exposure
             *  @param Pgas: Inert gas pressure in the compartment after the exposure time (ATM * 0.79 N2 or other mixture)
             *  @param te: Exposure time in minutes
             *  @param tht: Tissue compartment half time in minutes
             *  @return The gas loading of a tissue compartment (ATA)
             */
            return Pbegin + (Pgas - Pbegin) * (1 - 2.0.pow(-te / tht))
        }

        // This tells us a compartments maximum depth it can ascend to without bubble formation
        fun compCeiling(Pcomp: Double, a: Double, b: Double): Double {
            /**
             * @param Pcomp: Inert gas pressure in compartment
             * @return The ceiling pressure of a tissue compartment before bubble formation occurs
             *           if below 1 bar, then we can ascend to surface without bubbles
             */
            var ceiling = (Pcomp - a) * b

            if (ceiling < 0) {
                ceiling = 1.0
            }

            return ceiling
        }
    }
}
