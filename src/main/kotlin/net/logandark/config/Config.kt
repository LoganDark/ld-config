package net.logandark.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigCategory
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.util.Identifier
import net.minecraft.util.registry.SimpleRegistry
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.HashMap

@Suppress("unused", "MemberVisibilityCanBePrivate")
open class Config(
	/**
	 * The filename of this config. It will be located in the `config` subdirectory of your minecraft game directory. On
	 * dedicated servers, it will be located in the `config` subdirectory of the working directory.
	 *
	 * This filename can include `/` path separators which will be translated to the platform's native path separator.
	 * That way, `modid/example.json` will work on Windows since it will be translated to `modid\example.json`.
	 */
	val filename: String,

	/**
	 * The version is used for data fixing. Increment this when you do something like rename a config option, but not
	 * when you add or remove them. Added config options will be at their default values, and removed config options
	 * will simply not exist when the config is next saved.
	 */
	val version: Int = 1
) {
	abstract class ConfigOption<T>(
		/**
		 * The key this option will be under in the config. Additionally defines the translation key.
		 *
		 * Translation keys for identifiers are $namespace.config.$path
		 */
		val identifier: Identifier,

		/**
		 * Identical categories are grouped together visually. This uses the same namespace as the identifier and
		 * also defines the translation key for the category.
		 *
		 * Translation keys for categories are $namespace.config.category.$category
		 */
		category: String,

		/**
		 * This value is also set when we fail to load from the config. This ensures pre-load values do not persist,
		 * even if the config file gets corrupted or something.
		 */
		val defaultValue: T,

		/**
		 * A function that is called whenever an entry is created by [buildEntry].
		 */
		var tweakEntry: (AbstractConfigListEntry<T>.() -> Unit)?
	) {
		val category = Identifier(identifier.namespace, category)
		val value = AtomicReference(defaultValue)

		val translationKey = identifier.namespace + ".config." + identifier.path
		val categoryTranslationKey = this.category.namespace + ".config.category." + this.category.path

		/**
		 * Gets the current value of this config option.
		 */
		fun get() = value.get()!!

		/**
		 * Sets the value of this config option. This does not immediately save it to the config; for that see
		 * [Config.save].
		 */
		fun set(newValue: T) = value.set(newValue)

		/**
		 * Creates an [AbstractConfigListEntry] for this config option. Remember to set its save consumer to [set] so
		 * that the value is applied when it's saved.
		 */
		protected abstract fun makeEntry(entryBuilder: ConfigEntryBuilder): AbstractConfigListEntry<T>

		/**
		 * Returns a config entry that can be used via [ConfigCategory.addEntry].
		 */
		fun buildEntry(entryBuilder: ConfigEntryBuilder): AbstractConfigListEntry<T> {
			val entry = makeEntry(entryBuilder)
			tweakEntry?.invoke(entry)
			return entry
		}

		/**
		 * Serializes [value] into a [JsonElement]. You should usually try to make a [JsonElement] that [deserialize]
		 * will accept.
		 */
		abstract fun serialize(): JsonElement

		/**
		 * Deserializes [JsonElement] into a value *of the same type as [value]*, but should NOT set [value]
		 * automatically.
		 *
		 * This function should throw [IllegalArgumentException] or some other exception if the provided [JsonElement]
		 * is invalid.
		 */
		abstract fun deserialize(jsonElement: JsonElement): T

		/**
		 * Like [deserialize], but calls [set] on the result. Useful in contexts where you might not know T
		 */
		fun deserializeToValue(jsonElement: JsonElement) {
			set(deserialize(jsonElement))
		}

		/**
		 * Resets [value] to the [defaultValue]. Useful in contexts where you might not know T
		 */
		fun resetToDefault() {
			value.set(defaultValue)
		}
	}

	val registry = SimpleRegistry<ConfigOption<*>>()

	/**
	 * Adds a [ConfigOption] to the registry.
	 */
	fun <T> add(configOption: ConfigOption<T>) = configOption.also {
		registry.add(configOption.identifier, configOption)
	}

	fun createConfigScreen(parent: Screen): Screen {
		val builder = ConfigBuilder.create()
		val entryBuilder = builder.entryBuilder()

		registry.forEach {
			val entry = it.buildEntry(entryBuilder)
			builder.getOrCreateCategory(it.categoryTranslationKey).addEntry(entry)
		}

		return builder
			.setParentScreen(parent)
			.setSavingRunnable(this::save)
			.build()
	}

	private val configDir =
		if (FabricLoader.getInstance().environmentType == EnvType.CLIENT)
			File(MinecraftClient.getInstance().runDirectory, "config")
		else
			File(".${File.separatorChar}config")

	private val configFile = File(configDir, filename.replace('/', File.separatorChar))
	private val gson: BetterGson = BetterGson(GsonBuilder().setPrettyPrinting().create(), "\t")

	fun save() {
		val json = JsonObject()

		json.addProperty("_version", version)

		registry.forEach {
			json.add(it.identifier.toString(), it.serialize())
		}

		configFile.parentFile.mkdirs()
		configFile.createNewFile()
		configFile.writeText(gson.toJson(json))
	}

	class DataFixerException(
		val dataFixer: (JsonObject.() -> Unit)?,
		val forVersion: Int?,
		message: String,
		cause: Throwable? = null
	) : RuntimeException(message, cause)

	val dataFixers: HashMap<Int, LinkedList<JsonObject.() -> Unit>> = HashMap()

	/**
	 * Registers a data fixer that will be run when the config being loaded has a version lower than or equal to
	 * [version].
	 *
	 * This data fixer will be run on load to upgrade a config from version `version` to version `version + 1`.
	 */
	fun registerDataFixer(version: Int, dataFixer: JsonObject.() -> Unit) {
		val list = dataFixers[version] ?: LinkedList()
		list.addLast(dataFixer)
		dataFixers[version] = list
	}

	/**
	 * See [registerDataFixer]. This registers a simple data fixer that renames a top-level key in the config from
	 * [oldName] to [newName]. Keys are the string forms of the identifiers you use for config options. For example,
	 * if you used `Identifier("modid", "option_1")` and want to upgrade it to `Identifier("modid", "option_2")` then
	 * you will call `registerRenameDataFixer(1, "modid:option_1", "modid:option_2")`, assuming you are upgrading from
	 * version 1 to version 2.
	 */
	fun registerRenameDataFixer(version: Int, oldName: String, newName: String) {
		registerDataFixer(version) {
			add(newName, get(oldName))
			remove(oldName)
		}
	}

	fun runDataFixers(json: JsonObject) {
		val versionElement = json.get("_version")
		val jsonVersion = if (versionElement is JsonPrimitive && versionElement.isNumber) {
			versionElement.asInt
		} else {
			println("WARNING: The _version key could not be found or is invalid in config $filename. Assuming version 1.")

			1
		}

		if (jsonVersion > version) {
			throw DataFixerException(
				null,
				null,
				"Config is too new! Config $filename is version $jsonVersion but we are only $version."
			)
		}

		for (dataFixerVersion in jsonVersion until version) {
			val dataFixers = dataFixers[dataFixerVersion]

			if (dataFixers == null) {
				println("WARNING: Version $dataFixerVersion has no data fixers.")
				println("You should not have incremented the version if there were no breaking changes.")
				println("This may indicate programmer error.")
				println("To suppress this message anyway, add a blank data fixer.")

				continue
			}

			dataFixers.forEach {
				val result = runCatching {
					it.invoke(json)
				}

				result.exceptionOrNull()?.let { exception ->
					println("WARNING: Data fixer for version $dataFixerVersion threw an exception.")
					println("Data fixing cannot continue and the config file $filename will not be loaded.")
					println("TELL THE MOD DEVELOPERS ABOUT THIS!")

					throw DataFixerException(
						it,
						dataFixerVersion,
						"Data fixer for version $dataFixerVersion threw an exception.",
						exception
					)
				}
			}
		}
	}

	fun load() {
		val json = try {
			gson.fromJson(configFile.readText(), JsonObject::class)
		} catch (ex: FileNotFoundException) {
			return
		}

		try {
			runDataFixers(json)
		} catch (dfe: DataFixerException) {
			dfe.printStackTrace()

			val forVersion = dfe.forVersion?.let { " upgrading from version $it" } ?: ""
			println("WARNING: Data fixing failed$forVersion. Config $filename will not be loaded.")

			return
		}

		registry.forEach {
			try {
				json.get(it.identifier.toString())?.let(it::deserializeToValue)
			} catch (exception: Throwable) {
				it.resetToDefault()

				println("WARNING: A config value (${it.identifier}) could not be deserialized from config $filename.")
				exception.printStackTrace()
			}
		}
	}
}
