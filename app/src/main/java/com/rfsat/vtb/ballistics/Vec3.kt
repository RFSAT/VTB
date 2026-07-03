package com.rfsat.vtb.ballistics

import kotlin.math.sqrt

/**
 * x = downrange (bore line), y = up, z = crosswind-positive-to-the-right
 * (i.e. right-hand rule looking down the bore from behind the rifle).
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    val length: Double get() = sqrt(x * x + y * y + z * z)
    companion object { val ZERO = Vec3(0.0, 0.0, 0.0) }
}
