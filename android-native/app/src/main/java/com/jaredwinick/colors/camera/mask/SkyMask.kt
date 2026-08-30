package com.jaredwinick.colors.camera.mask

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

data class NormalizedPoint(val x: Double, val y: Double)

data class NormalizedRectangle(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

data class SkyMaskConfig(
    val schemaVersion: Int,
    val coordinateSpace: String,
    val minimumIncludedFraction: Double,
    val minimumIncludedPixels: Int,
    val includePolygon: List<NormalizedPoint>,
    val excludePolygons: List<List<NormalizedPoint>>,
    val excludeRectangles: List<NormalizedRectangle>,
) {
    fun requireValid(): SkyMaskConfig {
        require(schemaVersion == 1) { "schema_version must be 1" }
        require(coordinateSpace == "normalized") { "coordinate_space must be normalized" }
        require(minimumIncludedFraction.isFinite() && minimumIncludedFraction in 0.0..1.0) {
            "minimum_included_fraction must be between 0 and 1"
        }
        require(minimumIncludedPixels >= 1) { "minimum_included_pixels must be positive" }
        validatePolygon(includePolygon, "include_polygon")
        excludePolygons.forEachIndexed { index, polygon ->
            validatePolygon(polygon, "exclude_polygons[$index]")
        }
        excludeRectangles.forEachIndexed { index, rectangle ->
            listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom)
                .forEachIndexed { component, value ->
                    requireNormalized(value, "exclude_rectangles[$index][$component]")
                }
            require(rectangle.left < rectangle.right && rectangle.top < rectangle.bottom) {
                "exclude_rectangles[$index] must have positive width and height"
            }
        }
        return this
    }

    private fun validatePolygon(points: List<NormalizedPoint>, label: String) {
        require(points.size >= 3) { "$label must contain at least three points" }
        points.forEachIndexed { index, point ->
            requireNormalized(point.x, "$label[$index][0]")
            requireNormalized(point.y, "$label[$index][1]")
        }
        val doubledArea = points.indices.sumOf { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            current.x * next.y - next.x * current.y
        }
        require(abs(doubledArea) > 1e-12) { "$label must enclose a non-zero area" }
    }

    private fun requireNormalized(value: Double, label: String) {
        require(value.isFinite() && value in 0.0..1.0) { "$label must be between 0 and 1" }
    }
}

object SkyMaskJson {
    fun decode(json: String): SkyMaskConfig {
        try {
            val value = JSONObject(json)
            return SkyMaskConfig(
                schemaVersion = integer(value, "schema_version"),
                coordinateSpace = value.optString("coordinate_space", ""),
                minimumIncludedFraction = number(value, "minimum_included_fraction"),
                minimumIncludedPixels = integer(value, "minimum_included_pixels"),
                includePolygon = polygon(value.getJSONArray("include_polygon"), "include_polygon"),
                excludePolygons = value.optJSONArray("exclude_polygons")
                    ?.let { polygons ->
                        List(polygons.length()) { index ->
                            polygon(polygons.getJSONArray(index), "exclude_polygons[$index]")
                        }
                    }
                    .orEmpty(),
                excludeRectangles = value.optJSONArray("exclude_rectangles")
                    ?.let { rectangles ->
                        List(rectangles.length()) { index ->
                            rectangle(rectangles.getJSONArray(index), "exclude_rectangles[$index]")
                        }
                    }
                    .orEmpty(),
            ).requireValid()
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: JSONException) {
            throw IllegalArgumentException("Mask JSON is malformed: ${error.message}", error)
        }
    }

    fun encode(config: SkyMaskConfig): String {
        config.requireValid()
        return JSONObject().apply {
            put("schema_version", config.schemaVersion)
            put("coordinate_space", config.coordinateSpace)
            put("minimum_included_fraction", config.minimumIncludedFraction)
            put("minimum_included_pixels", config.minimumIncludedPixels)
            put("include_polygon", polygonJson(config.includePolygon))
            put("exclude_polygons", JSONArray().apply {
                config.excludePolygons.forEach { put(polygonJson(it)) }
            })
            put("exclude_rectangles", JSONArray().apply {
                config.excludeRectangles.forEach { rectangle ->
                    put(JSONArray(listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom)))
                }
            })
        }.toString(2) + "\n"
    }

    private fun polygon(value: JSONArray, label: String): List<NormalizedPoint> {
        require(value.length() >= 3) { "$label must contain at least three points" }
        return List(value.length()) { index ->
            val point = value.optJSONArray(index)
                ?: throw IllegalArgumentException("$label[$index] must be an [x, y] pair")
            require(point.length() == 2) { "$label[$index] must be an [x, y] pair" }
            NormalizedPoint(
                number(point, 0, "$label[$index][0]"),
                number(point, 1, "$label[$index][1]"),
            )
        }
    }

    private fun rectangle(value: JSONArray, label: String): NormalizedRectangle {
        require(value.length() == 4) { "$label must be [left, top, right, bottom]" }
        return NormalizedRectangle(
            left = number(value, 0, "$label[0]"),
            top = number(value, 1, "$label[1]"),
            right = number(value, 2, "$label[2]"),
            bottom = number(value, 3, "$label[3]"),
        )
    }

    private fun polygonJson(points: List<NormalizedPoint>): JSONArray = JSONArray().apply {
        points.forEach { point -> put(JSONArray(listOf(point.x, point.y))) }
    }

    private fun integer(value: JSONObject, key: String): Int {
        val raw = value.opt(key)
        require(raw is Int || raw is Long) { "$key must be an integer" }
        val long = (raw as Number).toLong()
        require(long in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is outside the integer range" }
        return long.toInt()
    }

    private fun number(value: JSONObject, key: String): Double {
        val raw = value.opt(key)
        require(raw is Number) { "$key must be a number" }
        return raw.toDouble()
    }

    private fun number(value: JSONArray, index: Int, label: String): Double {
        val raw = value.opt(index)
        require(raw is Number) { "$label must be a number" }
        return raw.toDouble()
    }
}
