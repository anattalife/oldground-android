package com.brandonthomas.oldground.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * The few GeoJSON shapes the app draws (pins, lines, outlines, public land), with just
 * enough reading and writing for the saved public-land squares.
 */

sealed class Geometry

class Point(val lng: Double, val lat: Double) : Geometry() {
    companion object {
        fun fromLngLat(lng: Double, lat: Double) = Point(lng, lat)
    }
}

class LineString(val points: List<Point>) : Geometry() {
    companion object {
        fun fromLngLats(points: List<Point>) = LineString(points)
    }
}

/** First ring is the outline, the rest are holes. */
class Polygon(val rings: List<List<Point>>) : Geometry() {
    companion object {
        fun fromLngLats(rings: List<List<Point>>) = Polygon(rings)
    }
}

class MultiPolygon(val polygons: List<Polygon>) : Geometry()

class Feature(val geometry: Geometry?, private val props: MutableMap<String, JsonPrimitive> = LinkedHashMap(), private val fid: String? = null) {
    fun addStringProperty(k: String, v: String) { props[k] = JsonPrimitive(v) }
    fun addNumberProperty(k: String, v: Number) { props[k] = JsonPrimitive(v) }
    fun addBooleanProperty(k: String, v: Boolean) { props[k] = JsonPrimitive(v) }

    fun has(k: String) = props[k]?.let { it !is JsonNull } == true
    fun string(k: String): String? = props[k]?.takeIf { it !is JsonNull }?.content
    fun number(k: String): Double? = props[k]?.doubleOrNull
    fun boolean(k: String): Boolean? = props[k]?.booleanOrNull

    /** Every property as text (empty for nulls). */
    fun propertyStrings(): Map<String, String> = props.mapValues { (_, v) -> if (v is JsonNull) "" else v.content }

    fun id(): String? = fid

    fun toJson(): String = buildJsonObject {
        put("type", "Feature")
        fid?.let { put("id", it) }
        put("geometry", geometry?.let(::geomJson) ?: JsonNull)
        put("properties", JsonObject(props))
    }.toString()

    companion object {
        fun fromGeometry(g: Geometry) = Feature(g)
    }
}

class FeatureCollection(private val list: List<Feature>) {
    fun features(): List<Feature> = list

    companion object {
        fun fromFeatures(list: List<Feature>) = FeatureCollection(list)
        fun fromFeature(f: Feature) = FeatureCollection(listOf(f))

        fun fromJson(text: String): FeatureCollection {
            val root = Json.parseToJsonElement(text).jsonObject
            val feats = (root["features"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val props = LinkedHashMap<String, JsonPrimitive>()
                (o["properties"] as? JsonObject)?.forEach { (k, v) -> if (v is JsonPrimitive) props[k] = v }
                val id = (o["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                Feature(parseGeom(o["geometry"]), props, id)
            }
            return FeatureCollection(feats)
        }
    }
}

private fun pt(e: JsonElement): Point {
    val a = e.jsonArray
    return Point(a[0].jsonPrimitive.doubleOrNull ?: 0.0, a[1].jsonPrimitive.doubleOrNull ?: 0.0)
}

private fun ring(e: JsonElement) = e.jsonArray.map(::pt)

private fun parseGeom(e: JsonElement?): Geometry? {
    val o = e as? JsonObject ?: return null
    val c = o["coordinates"] ?: return null
    return runCatching {
        when (o["type"]?.jsonPrimitive?.content) {
            "Point" -> pt(c)
            "LineString" -> LineString(ring(c))
            "Polygon" -> Polygon(c.jsonArray.map(::ring))
            "MultiPolygon" -> MultiPolygon(c.jsonArray.map { p -> Polygon(p.jsonArray.map(::ring)) })
            else -> null
        }
    }.getOrNull()
}

private fun ptJson(p: Point) = buildJsonArray { add(JsonPrimitive(p.lng)); add(JsonPrimitive(p.lat)) }
private fun ringJson(r: List<Point>) = JsonArray(r.map(::ptJson))

private fun geomJson(g: Geometry): JsonObject = when (g) {
    is Point -> buildJsonObject { put("type", "Point"); put("coordinates", ptJson(g)) }
    is LineString -> buildJsonObject { put("type", "LineString"); put("coordinates", ringJson(g.points)) }
    is Polygon -> buildJsonObject { put("type", "Polygon"); put("coordinates", JsonArray(g.rings.map(::ringJson))) }
    is MultiPolygon -> buildJsonObject {
        put("type", "MultiPolygon")
        put("coordinates", JsonArray(g.polygons.map { p -> JsonArray(p.rings.map(::ringJson)) }))
    }
}
