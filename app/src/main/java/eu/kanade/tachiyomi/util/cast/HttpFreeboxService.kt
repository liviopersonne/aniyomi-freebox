package eu.kanade.tachiyomi.util.cast

object HttpFreeboxService {

    // 0 -> Disconnected ; 1 -> Pending ; 2 -> Connected but no Freebox Player ; 3 -> Connected
    var state: Int = 0
}
