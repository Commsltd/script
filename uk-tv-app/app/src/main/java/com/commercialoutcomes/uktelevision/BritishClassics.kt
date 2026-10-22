package com.commercialoutcomes.uktelevision

/** Search suggestions, not assertions that a provider has playable episodes. */
object BritishClassics {
    val titles = listOf("Open All Hours", "Only Fools and Horses", "'Allo 'Allo!",
        "New Tricks", "Minder", "Porridge", "The Morecambe and Wise Show")

    fun query(value: String): String = when(normalTitle(value)) {
        "ofah" -> "Only Fools and Horses"
        "morcombe and wise", "morecambe and wise", "morecambe wise" -> "The Morecambe and Wise Show"
        else -> value.trim()
    }
}
