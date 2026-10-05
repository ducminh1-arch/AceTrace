package com.acetrace.app

import com.acetrace.app.core.curve.TimeMapping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs

class TimeMappingGoldenTest {

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
    fun testTimeMappingAgainstGoldenVector() {
        val jsonStr = readGoldenJson("time_mapping_golden.json")
        val json = Json.parseToJsonElement(jsonStr).jsonObject

        val fStart = json["f_start"]!!.jsonPrimitive.int
        val fApex = json["f_apex"]!!.jsonPrimitive.int
        val fLanding = json["f_landing"]!!.jsonPrimitive.int

        val samplesArray = json["samples"]!!.jsonArray
        for (sampleElement in samplesArray) {
            val sObj = sampleElement.jsonObject
            val frame = sObj["frame"]!!.jsonPrimitive.int
            val expProgress = sObj["progress"]!!.jsonPrimitive.double.toFloat()
            val expU = sObj["u"]!!.jsonPrimitive.double.toFloat()

            val state = TimeMapping.computeFlightState(frame, fStart, fApex, fLanding)

            val errU = abs(state.u - expU)
            val errProgress = abs(state.progress - expProgress)

            assertEquals("TimeMapping u mismatch at frame=$frame (err=$errU)", expU, state.u, 1e-4f)
            assertEquals("TimeMapping progress mismatch at frame=$frame (err=$errProgress)", expProgress, state.progress, 1e-4f)
        }
    }
}
