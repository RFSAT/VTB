package com.rfsat.vtb.results

import com.rfsat.vtb.wind.WindSample

/**
 * Process-lifetime holder for the last capture's results. A real app with
 * a shot log would persist this (Room/SQLite); for now one shot's worth
 * of data at a time is enough for the workflow: capture -> analyze -> results.
 */
object AnalysisSession {
    var windSamples: List<WindSample> = emptyList()
    var adjustment: ScopeAdjustment? = null
    var targetDistanceYd: Double = 100.0
}
