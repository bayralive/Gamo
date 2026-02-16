package com.bayra.shared
object PricingEngine {
    fun calculate(base: Double, dist: Double, rate: Double, isHr: Boolean): Int {
        val total = if(isHr) base else (base + (dist * rate))
        return (total * 1.15).toInt() // The 15% Bayra Cut
    }
}
