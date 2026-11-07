package nz.ridgeline.nativeapp

/** Network transfers only; cache reads, decoding and uploads have separate caps. */
data class DownloadLimits(val moving:Int=8,val stationary:Int=12,val background:Int=2) {
    enum class Mode{MOVING,STATIONARY,BACKGROUND}
    fun normalized()=DownloadLimits(moving.coerceIn(1,MAX),stationary.coerceIn(1,MAX),background.coerceIn(1,MAX))
    fun value(mode:Mode)=when(mode){Mode.MOVING->moving;Mode.STATIONARY->stationary;Mode.BACKGROUND->background}
    fun with(mode:Mode,value:Int)=when(mode){Mode.MOVING->copy(moving=value);Mode.STATIONARY->copy(stationary=value);Mode.BACKGROUND->copy(background=value)}.normalized()
    companion object {
        const val MAX=50
        // Transport owns live admission. Both HTTP dispatchers accommodate the
        // largest permitted foreground + background setting without a second cap.
        const val HTTP_CAPACITY=MAX*2
        val DEFAULT=DownloadLimits()
    }
}
