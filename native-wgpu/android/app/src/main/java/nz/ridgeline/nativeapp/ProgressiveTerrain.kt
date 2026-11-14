package nz.ridgeline.nativeapp

/** Which terrain tasks may show a provisional measured interior. Plan ranks:
 * 0 focus parent, 1-4 focus leaves, 8+ fallback parents, 64+ other leaves in
 * nearest-first order. The four focus leaves and the next twelve nearest leaves
 * are eligible; staged interiors are bounded by the renderer's own limit. */
internal object ProgressiveTerrain {
    const val MAX_STAGED=16
    fun eligibleRank(priority:Int)=priority<=4||priority in 64 until 76
}
