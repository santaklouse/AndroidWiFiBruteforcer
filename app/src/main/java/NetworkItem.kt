data class NetworkItem(
    val ssid: String,
    val status: Status,  // SUCCESS, FAILED, OPEN
    val signal: Int
)

enum class Status { SUCCESS, FAILED, OPEN }