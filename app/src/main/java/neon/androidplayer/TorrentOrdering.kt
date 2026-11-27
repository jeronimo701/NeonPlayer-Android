package neon.androidplayer

internal fun torrentDate(raw:String):Long=runCatching{java.time.Instant.parse(raw).toEpochMilli()}.recoverCatching{java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()}.recoverCatching{java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()}.recoverCatching{java.time.LocalDate.parse(raw).toEpochDay()*86400000}.getOrDefault(Long.MIN_VALUE)

/** Unknown values sort last; a real zero seed/peer count remains a real zero. */
internal fun orderedTorrents(hits:List<TorrentHit>,order:String):List<TorrentHit> = when(order){
    "size"->hits.sortedByDescending{it.size}
    "sizeAsc"->hits.sortedWith(compareBy<TorrentHit>{it.size<0}.thenBy{it.size})
    "peers"->hits.sortedByDescending{it.peers}
    "date"->hits.sortedByDescending{torrentDate(it.published)}
    "updated"->hits.sortedByDescending{torrentDate(it.updated)}
    "quality"->hits.sortedByDescending{it.quality}
    "name"->hits.sortedWith(Comparator{a,b->naturalCompare(a.title,b.title)})
    else->hits.sortedByDescending{it.seeds}
}
