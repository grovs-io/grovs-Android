package io.grovs.utils

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.bind.DateTypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
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

class LSJsonDateTypeAdapterFactory : TypeAdapterFactory {
    val dateFormat = run {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")

        format
    }
    val dateFormat2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSSS'Z'", Locale.US)
    val dateFormat3 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val dateFormat4 = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {

        val originAdapter = DateTypeAdapter.FACTORY.create(gson,type)

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
                        var string = input.nextString()
                        if (instant == null) {
                            tryOptional {
                                instant = Instant.parse(string)
                            }
                        }
//                        if (instant == null) {
//                            tryOptional {
//                                instant = dateFormat2.parse(string)
//                            }
//                        }
//                        if (instant == null) {
//                            tryOptional {
//                                instant = dateFormat3.parse(string)
//                            }
//                        }
//                        if (instant == null) {
//                            instant = dateFormat4.parse(string)
//                        }
                        return instant
                    }
                    input.peek() == JsonToken.NUMBER -> Instant.ofEpochSecond(input.nextLong())
                    else -> null
                }


            }

        } as TypeAdapter<T>
    }
}