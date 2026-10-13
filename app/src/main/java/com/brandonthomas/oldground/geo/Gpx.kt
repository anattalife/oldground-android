package com.brandonthomas.oldground.geo

import java.time.Instant

object Gpx {
    data class Waypoint(val name: String, val geo: LngLat, val note: String? = null, val time: String? = null)
    data class TrackPoint(val geo: LngLat, val timeMs: Long)
    data class Track(val name: String, val points: List<TrackPoint>)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun f(v: Double) = String.format(java.util.Locale.US, "%.7f", v)

    fun build(waypoints: List<Waypoint>, tracks: List<Track>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OldGround\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in waypoints) {
            sb.append("  <wpt lat=\"${f(w.geo.lat)}\" lon=\"${f(w.geo.lng)}\">\n")
            sb.append("    <name>${esc(w.name)}</name>\n")
            if (!w.note.isNullOrBlank()) sb.append("    <desc>${esc(w.note)}</desc>\n")
            if (w.time != null) sb.append("    <time>${w.time}</time>\n")
            sb.append("  </wpt>\n")
        }
        for (t in tracks) {
            sb.append("  <trk>\n    <name>${esc(t.name)}</name>\n    <trkseg>\n")
            for (p in t.points) {
                sb.append("      <trkpt lat=\"${f(p.geo.lat)}\" lon=\"${f(p.geo.lng)}\"><time>${Instant.ofEpochMilli(p.timeMs)}</time></trkpt>\n")
            }
            sb.append("    </trkseg>\n  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
