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
                        var string = input.nextString()
                        if (instant == null) {
                            tryOptional {
                                instant = InstantCompat.parse(string)
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
                    input.peek() == JsonToken.NUMBER -> InstantCompat.ofEpochSecond(input.nextLong())
                    else -> null
                }


            }

        } as TypeAdapter<T>
    }
}