package com.alican.devicescope

/**
 * How much we trust a shown value.
 *  OK         -> read directly from the platform / a real sensor
 *  ESTIMATE   -> computed by this app from real inputs (formula is always shown in the app)
 *  RESTRICTED -> exists on the device but the platform blocks unprivileged apps
 *  MISSING    -> the device simply does not store this anywhere readable
 */
enum class Flag { OK, ESTIMATE, RESTRICTED, MISSING }

data class Item(
    val label: String,
    val value: String,
    val detail: String? = null,
    val flag: Flag = Flag.OK,
    /** Where the value came from, shown as a small source line. */
    val source: String? = null
)

data class Section(
    val title: String,
    val items: List<Item>
)
