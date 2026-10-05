package com.acetrace.app

import com.acetrace.app.core.curve.BezierPath
import com.acetrace.app.core.curve.CatmullRomSegment
import com.acetrace.app.core.model.Keypoint
import com.acetrace.app.core.model.Point2D
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs

class CurveGoldenTest {

    private fun readGoldenJson(filename: String): String {
        val directFile = File("../../aceTrace_spec/golden/$filename")
        if (directFile.exists()) {
            return directFile.readText()
        }
        val altFile = File("../aceTrace_spec/golden/$filename")
        if (altFile.exists()) {
            return altFile.readText()
        }
        val resource = javaClass.classLoader?.getResourceAsStream("golden/$filename")
        if (resource != null) {
            return resource.bufferedReader().readText()
        }
        throw IllegalStateException("Golden vector file not found: $filename")
    }

    @Test
    fun testBezierAgainstGoldenVector() {
        val jsonStr = readGoldenJson("bezier_golden.json")
        val json = Json.parseToJsonElement(jsonStr).jsonObject

        val keypointsArray = json["keypoints"]!!.jsonArray
        val keypoints = keypointsArray.map { kpElement ->
            val obj = kpElement.jsonObject
            val role = obj["role"]!!.jsonPrimitive.content
            val pt = obj["point"]!!.jsonObject
            val hIn = obj["handleIn"]?.jsonObject?.let {
                Point2D(it["x"]!!.jsonPrimitive.double.toFloat(), it["y"]!!.jsonPrimitive.double.toFloat())
            }
            val hOut = obj["handleOut"]?.jsonObject?.let {
                Point2D(it["x"]!!.jsonPrimitive.double.toFloat(), it["y"]!!.jsonPrimitive.double.toFloat())
            }
            Keypoint(
                role = role,
                frameIndex = 0,
                x = pt["x"]!!.jsonPrimitive.double.toFloat(),
                y = pt["y"]!!.jsonPrimitive.double.toFloat(),
                handleIn = hIn,
                handleOut = hOut
            )
        }

        val bezierPath = BezierPath.fromKeypoints(keypoints)

        val samplesArray = json["samples"]!!.jsonArray
        for (sampleElement in samplesArray) {
            val sObj = sampleElement.jsonObject
            val u = sObj["u"]!!.jsonPrimitive.double.toFloat()
            val expectedPt = sObj["point"]!!.jsonObject
            val expX = expectedPt["x"]!!.jsonPrimitive.double.toFloat()
            val expY = expectedPt["y"]!!.jsonPrimitive.double.toFloat()

            val actualPt = bezierPath.point(u)

            val errX = abs(actualPt.x - expX)
            val errY = abs(actualPt.y - expY)

            assertEquals("Bezier X mismatch at u=$u (err=$errX)", expX, actualPt.x, 1e-4f)
            assertEquals("Bezier Y mismatch at u=$u (err=$errY)", expY, actualPt.y, 1e-4f)
        }
    }

    @Test
    fun testCentripetalCatmullRomAgainstGoldenVector() {
        val jsonStr = readGoldenJson("catmull_rom_golden.json")
        val json = Json.parseToJsonElement(jsonStr).jsonObject

        val controlPoints = json["control_points"]!!.jsonArray.map { ptEl ->
            val pt = ptEl.jsonObject
            Point2D(pt["x"]!!.jsonPrimitive.double.toFloat(), pt["y"]!!.jsonPrimitive.double.toFloat())
        }

        val segment = CatmullRomSegment(
            controlPoints[0],
            controlPoints[1],
            controlPoints[2],
            controlPoints[3],
            alpha = 0.5f
        )

        val samplesArray = json["samples"]!!.jsonArray
        for (sampleElement in samplesArray) {
            val sObj = sampleElement.jsonObject
            val t = sObj["t"]!!.jsonPrimitive.double.toFloat()
            val expectedPt = sObj["point"]!!.jsonObject
            val expX = expectedPt["x"]!!.jsonPrimitive.double.toFloat()
            val expY = expectedPt["y"]!!.jsonPrimitive.double.toFloat()

            val actualPt = segment.evaluate(t)

            val errX = abs(actualPt.x - expX)
            val errY = abs(actualPt.y - expY)

            assertEquals("Catmull-Rom X mismatch at t=$t (err=$errX)", expX, actualPt.x, 1e-4f)
            assertEquals("Catmull-Rom Y mismatch at t=$t (err=$errY)", expY, actualPt.y, 1e-4f)
        }
    }
}
