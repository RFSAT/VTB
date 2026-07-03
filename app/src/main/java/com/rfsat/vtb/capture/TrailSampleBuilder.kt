package com.rfsat.vtb.capture

import com.rfsat.vtb.ballistics.Atmosphere
import com.rfsat.vtb.ballistics.BallisticsEngine
import com.rfsat.vtb.profiles.BulletProfile
import com.rfsat.vtb.wind.TrailSample

object TrailSampleBuilder {

    /**
     * [observations] should already be trimmed to the shot's actual flight
     * (from muzzle exit to impact/frame-out) and sorted by time.
     * Downrange distance at each observation's timestamp comes from the
     * baseline (zero-wind) trajectory — a reasonable approximation since
     * crosswind has only a second-order effect on downrange velocity decay.
     */
    fun build(
        observations: List<PixelObservation>,
        calibration: TrailCalibration,
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        maxRangeM: Double,
        minConfidence: Double = 0.02
    ): List<TrailSample> {
        val baseline = BallisticsEngine.simulate(
            bullet, atmosphere, pitchRad = 0.0, yawRad = 0.0, maxRangeM = maxRangeM, sampleEveryS = 0.001
        )
        if (baseline.isEmpty()) return emptyList()

        fun downrangeAt(t: Double): Double {
            if (t <= baseline.first().timeS) return baseline.first().position.x
            if (t >= baseline.last().timeS) return baseline.last().position.x
            val idx = baseline.indexOfLast { it.timeS <= t }.coerceIn(0, baseline.size - 2)
            val a = baseline[idx]; val b = baseline[idx + 1]
            val f = (t - a.timeS) / (b.timeS - a.timeS)
            return a.position.x + f * (b.position.x - a.position.x)
        }

        return observations.filter { it.confidence >= minConfidence }.map { obs ->
            val downrangeM = downrangeAt(obs.timestampS)
            val (lateral, vertical) = calibration.toWorldOffsets(obs.pixelX, obs.pixelY, downrangeM)
            TrailSample(timeS = obs.timestampS, downrangeM = downrangeM, lateralM = lateral, verticalM = vertical)
        }
    }
}
