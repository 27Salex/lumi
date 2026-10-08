package io.github.salex27.lumi.domain.pcview

/**
 * Level 1 remote control: Lumi only hands the PC's address to a standard remote desktop app (Microsoft Remote Desktop /
 * Windows App). No input is injected by Lumi and the token is never part of the link. Pure, tested.
 */
object RemoteControl {
    /** Microsoft Remote Desktop (Windows App) for Android. */
    const val APP_PACKAGE = "com.microsoft.rdc.androidx"
    const val MARKET_URI = "market://details?id=$APP_PACKAGE"
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=$APP_PACKAGE"
    private val HOST = Regex("^[A-Za-z0-9.-]+$")

    /**
     * The documented Remote Desktop URI scheme: rdp://full%20address=s:HOST:PORT&audiomode=i:0.
     * Null when the host or port is not valid (the caller shows "set the address first").
     */
    fun rdpUri(host: String, port: Int): String? {
        if (!HOST.matches(host) || port !in 1..65535) return null
        return "rdp://full%20address=s:${encode(host)}:$port&audiomode=i:0&screen%20mode%20id=i:2"
    }

    /** Percent-encodes everything but unreserved characters (hosts are already restricted, this is defence in depth). */
    fun encode(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xff).toChar()
            if (ch.isLetterOrDigit() && ch.code < 128 || ch == '-' || ch == '.' || ch == '_' || ch == '~') append(ch)
            else append('%').append("%02X".format(b.toInt() and 0xff))
        }
    }

    /** What to show the user before opening: host:port only. */
    fun display(host: String, port: Int) = "$host:$port"
}
