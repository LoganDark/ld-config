package net.logandark.config

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonIOException
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import kotlin.reflect.KClass

/**
 * Modifies [Gson] to support custom indentation. I know it's ugly, but Google is a butt about their indentation and I
 * really wanted clean tabs over spaces.
 */
@Suppress("MemberVisibilityCanBePrivate")
class BetterGson(val gson: Gson = Gson(), var indent: String? = null) {
	private fun getField(name: String, instance: Any? = gson): Any? {
		val field = gson.javaClass.getDeclaredField(name)
		val wasAccessible = field.isAccessible

		try {
			field.isAccessible = true
			return field.get(instance)
		} finally {
			field.isAccessible = wasAccessible
		}
	}

	@Throws(IOException::class)
	fun newJsonWriter(writer: Writer): JsonWriter {
		if (getField("generateNonExecutableJson") as Boolean)
			writer.write(getField("JSON_NON_EXECUTABLE_PREFIX", null) as String)

		val jsonWriter = JsonWriter(writer)

		if (getField("prettyPrinting") as Boolean)
			jsonWriter.setIndent(indent ?: "  ")

		jsonWriter.serializeNulls = getField("serializeNulls") as Boolean

		return jsonWriter
	}

	fun toJson(jsonElement: JsonElement): String {
		val writer = StringWriter()

		try {
			val jsonWriter = newJsonWriter(Streams.writerForAppendable(writer))
			jsonWriter.isLenient = true
			jsonWriter.isHtmlSafe = getField("htmlSafe") as Boolean
			jsonWriter.serializeNulls = getField("serializeNulls") as Boolean

			try {
				Streams.write(jsonElement, jsonWriter)
			} catch (e: IOException) {
				throw JsonIOException(e)
			}
		} catch (e: IOException) {
			throw JsonIOException(e)
		}

		return writer.toString()
	}

	fun <T : JsonElement> fromJson(json: String, klass: KClass<T>): T {
		return gson.fromJson(json, klass.java)
	}
}
