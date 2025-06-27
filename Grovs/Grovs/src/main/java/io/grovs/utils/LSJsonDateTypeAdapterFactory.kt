package io.grovs.utils

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.bind.DateTypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

inline fun <T> tryOptional(expression: () -> T): T? {
    return try {
        expression()
    } catch (ex: Throwable) {
        null
    }
}

class LSJsonInstantCompatTypeAdapterFactory : TypeAdapterFactory {
    val dateFormat = run {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")

        format
    }

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != InstantCompat::class.java){
            return null
        }

        return object: TypeAdapter<InstantCompat>() {

            @Throws(IOException::class)
            override  fun write(out: JsonWriter, value: InstantCompat?) {
                if (value == null)
                    out.nullValue()
                else {
                    val date = value.toDate()
                    out.value(dateFormat.format(date))
                }
            }

            @Throws(IOException::class)
            override  fun read(input: JsonReader?): InstantCompat? {
                return when {
                    input ==  null -> null
                    input.peek() === JsonToken.NULL -> { input.nextNull();  return null }
                    input.peek() == JsonToken.STRING -> {
                        var instant: InstantCompat? = null
                        val string = input.nextString()
                        tryOptional {
                            instant = InstantCompat.parse(string)
                        }

                        instant
                    }
                    input.peek() == JsonToken.NUMBER -> InstantCompat.ofEpochSecond(input.nextLong())
                    else -> null
                }
            }
        } as TypeAdapter<T>
    }
}

class LSJsonDateTypeAdapterFactory : TypeAdapterFactory {
    val dateFormat = run {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")

        format
    }

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != Date::class.java){
            return null
        }

        return object: TypeAdapter<Date>() {

            @Throws(IOException::class)
            override  fun write(out: JsonWriter, value: Date?) {
                if (value == null)
                    out.nullValue()
                else {
                    out.value(dateFormat.format(value))
                }
            }

            @Throws(IOException::class)
            override  fun read(input: JsonReader?): Date? {
                return when {
                    input ==  null -> null
                    input.peek() === JsonToken.NULL -> { input.nextNull();  return null }
                    input.peek() == JsonToken.STRING -> {
                        var instant: Date? = null
                        val string = input.nextString()
                        tryOptional {
                            instant = dateFormat.parse(string)
                        }

                        instant
                    }
                    input.peek() == JsonToken.NUMBER -> Date(input.nextLong())
                    else -> null
                }
            }
        } as TypeAdapter<T>
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class LSJsonInstantTypeAdapterFactory : TypeAdapterFactory {
    val dateFormat = run {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")

        format
    }

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != Instant::class.java){
            return null
        }

        return object: TypeAdapter<Instant>() {

            @Throws(IOException::class)
            override  fun write(out: JsonWriter, value: Instant?) {
                if (value == null)
                    out.nullValue()
                else {
                    val date = Date.from(value)
                    out.value(dateFormat.format(date))
                }
            }

            @Throws(IOException::class)
            override  fun read(input: JsonReader?): Instant? {
                return when {
                    input ==  null -> null
                    input.peek() === JsonToken.NULL -> { input.nextNull();  return null }
                    input.peek() == JsonToken.STRING -> {
                        var instant: Instant? = null
                        val string = input.nextString()
                        tryOptional {
                            instant = Instant.parse(string)
                        }

                        instant
                    }
                    input.peek() == JsonToken.NUMBER -> Instant.ofEpochSecond(input.nextLong())
                    else -> null
                }
            }
        } as TypeAdapter<T>
    }
}