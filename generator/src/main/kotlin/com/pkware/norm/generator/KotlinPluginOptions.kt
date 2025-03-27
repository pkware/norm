package com.pkware.norm.generator

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okio.Buffer
import plugin.GenerateRequest

/**
 * Options in the sqlc YAML for the Kotlin code-gen plugin.
 *
 * @param packageName Name of the package in which code should be generated.
 */
@JsonClass(generateAdapter = true)
data class KotlinPluginOptions(
  val packageName: String,
)

/**
 * Inflates the JSON [GenerateRequest.plugin_options].
 */
@OptIn(ExperimentalStdlibApi::class)
fun GenerateRequest.getPluginOptions(moshi: Moshi): KotlinPluginOptions {
  val adapter = moshi.adapter<KotlinPluginOptions>()
  val buffer = Buffer()
  buffer.write(plugin_options)
  return adapter.fromJson(buffer)!!
}
