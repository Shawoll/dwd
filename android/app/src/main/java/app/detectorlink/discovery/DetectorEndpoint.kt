package app.detectorlink.discovery

data class DetectorEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val id: String? = null,
    val wsPath: String = "/ws",
    val uiPath: String = "/",
) {
    private val authority: String
        get() = (if (':' in host) "[$host]" else host) + ":$port"

    val wsUrl: String get() = "ws://$authority$wsPath"
    val uiUrl: String get() = "http://$authority$uiPath"

    companion object {
        fun parseManual(input: String, defaultPort: Int = 8080): DetectorEndpoint? {
            val text = input.trim().removePrefix("http://").removePrefix("ws://").trimEnd('/')
            if (text.isEmpty()) return null
            val host = text.substringBeforeLast(':', text)
            val port = if (host == text) defaultPort else text.substringAfterLast(':').toIntOrNull() ?: return null
            if (port !in 1..65535 || host.isBlank()) return null
            return DetectorEndpoint(name = host, host = host, port = port)
        }
    }
}
