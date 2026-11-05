package com.commercialoutcomes.uktelevision

/** A finite bag avoids repeating a domain before all eligible domains are visited. */
class PersonalRotation(private val shuffle: (List<Int>) -> List<Int> = { it.shuffled() }) {
    private val bag=mutableListOf<Int>()
    private var previousPool=emptyList<Int>()
    fun reset() { bag.clear();previousPool=emptyList() }
    fun next(current:Int,pool:List<Int>,random:Boolean):Int {
        val eligible=pool.distinct().filter { it in 0..6 }
        if(eligible.isEmpty())return current
        if(!random)return eligible[(eligible.indexOf(current)+1).mod(eligible.size)]
        if(previousPool!=eligible){bag.clear();previousPool=eligible}
        bag.removeAll { it !in eligible || it==current }
        if(bag.isEmpty())bag.addAll(shuffle(eligible.filter { it!=current }))
        return if(bag.isEmpty())current else bag.removeAt(0)
    }
}
