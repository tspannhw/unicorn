/*******************************************************************************
 * (C) Copyright 2015 ADP, LLC.
 *   
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package unicorn.json

import java.nio.ByteBuffer
import unicorn.oid.BsonObjectId

/** Json serializer helper functions.
  * @author Haifeng Li
  */
trait BaseJsonSerializer extends JsonSerializer {
  /** End of document */
  val END_OF_DOCUMENT             : Byte = 0x00

  /** End of string */
  val END_OF_STRING               : Byte = 0x00

  /** Type markers */
  val TYPE_DOUBLE                 : Byte = 0x01
  val TYPE_STRING                 : Byte = 0x02
  val TYPE_DOCUMENT               : Byte = 0x03
  val TYPE_ARRAY                  : Byte = 0x04
  val TYPE_BINARY                 : Byte = 0x05
  val TYPE_UNDEFINED              : Byte = 0x06
  val TYPE_OBJECTID               : Byte = 0x07
  val TYPE_BOOLEAN                : Byte = 0x08
  val TYPE_DATETIME               : Byte = 0x09
  val TYPE_NULL                   : Byte = 0x0A
  val TYPE_REGEX                  : Byte = 0x0B
  val TYPE_DBPOINTER              : Byte = 0x0C
  val TYPE_JAVASCRIPT             : Byte = 0x0D
  val TYPE_SYMBOL                 : Byte = 0x0E
  val TYPE_JAVASCRIPT_WITH_SCOPE  : Byte = 0x0F
  val TYPE_INT32                  : Byte = 0x10
  val TYPE_TIMESTAMP              : Byte = 0x11
  val TYPE_INT64                  : Byte = 0x12
  val TYPE_MINKEY                 : Byte = 0xFF.toByte
  val TYPE_MAXKEY                 : Byte = 0x7F

  /** Binary subtypes */
  val BINARY_SUBTYPE_GENERIC      : Byte = 0x00
  val BINARY_SUBTYPE_FUNCTION     : Byte = 0x01
  val BINARY_SUBTYPE_BINARY_OLD   : Byte = 0x02
  val BINARY_SUBTYPE_UUID_OLD     : Byte = 0x03
  val BINARY_SUBTYPE_UUID         : Byte = 0x04
  val BINARY_SUBTYPE_MD5          : Byte = 0x05
  val BINARY_SUBTYPE_USER_DEFINED : Byte = 0x80.toByte

  val TRUE                        : Byte = 0x01
  val FALSE                       : Byte = 0x00

  /** Encoding of "undefined" */
  val undefined = Array(TYPE_UNDEFINED)
  val `null` = Array(TYPE_NULL)

  def serialize(buffer: ByteBuffer, string: Option[String]): Unit = {
    if (string.isDefined) {
      serialize(buffer, string.get)
    }
  }

  def serialize(buffer: ByteBuffer, string: String): Unit = {
    buffer.put(string.getBytes(charset))
    buffer.put(END_OF_STRING)
  }

  def serialize(buffer: ByteBuffer, json: JsBoolean, ename: Option[String]): Unit = {
    buffer.put(TYPE_BOOLEAN)
    serialize(buffer, ename)
    buffer.put(if (json.value) TRUE else FALSE)
  }

  def serialize(buffer: ByteBuffer, json: JsInt, ename: Option[String]): Unit = {
    buffer.put(TYPE_INT32)
    serialize(buffer, ename)
    // We flip the leading bit so that negative values will
    // sort before 0 in ASC order for bit strings. This is
    // important as integers on JVM are all signed.
    buffer.putInt(json.value ^ 0x80000000)
  }

  def serialize(buffer: ByteBuffer, json: JsLong, ename: Option[String]): Unit = {
    buffer.put(TYPE_INT64)
    serialize(buffer, ename)
    // We flip the leading bit so that negative values will
    // sort before 0 in ASC order for bit strings. This is
    // important as integers on JVM are all signed.
    buffer.putLong(json.value ^ 0x8000000000000000L)
  }

  def serialize(buffer: ByteBuffer, json: JsDouble, ename: Option[String]): Unit = {
    buffer.put(TYPE_DOUBLE)
    serialize(buffer, ename)
    buffer.putDouble(json.value)
  }

  def serialize(buffer: ByteBuffer, json: JsString, ename: Option[String]): Unit = {
    buffer.put(TYPE_STRING)
    serialize(buffer, ename)
    val bytes = json.value.getBytes(charset)
    buffer.putInt(bytes.length)
    buffer.put(bytes)
  }

  def serialize(buffer: ByteBuffer, json: JsDate, ename: Option[String]): Unit = {
    buffer.put(TYPE_DATETIME)
    serialize(buffer, ename)
    buffer.putLong(json.value.getTime)
  }

  def serialize(buffer: ByteBuffer, json: JsObjectId, ename: Option[String]): Unit = {
    buffer.put(TYPE_OBJECTID)
    serialize(buffer, ename)
    buffer.put(json.value.id)
  }

  def serialize(buffer: ByteBuffer, json: JsUUID, ename: Option[String]): Unit = {
    buffer.put(TYPE_BINARY)
    serialize(buffer, ename)
    buffer.putInt(16)
    buffer.put(BINARY_SUBTYPE_UUID)
    buffer.putLong(json.value.getMostSignificantBits)
    buffer.putLong(json.value.getLeastSignificantBits)
  }

  def serialize(buffer: ByteBuffer, json: JsBinary, ename: Option[String]): Unit = {
    buffer.put(TYPE_BINARY)
    serialize(buffer, ename)
    buffer.putInt(json.value.size)
    buffer.put(BINARY_SUBTYPE_GENERIC)
    buffer.put(json.value)
  }

  def cstring(buffer: ByteBuffer): String = {
    val str = new collection.mutable.ArrayBuffer[Byte](64)
    var b = buffer.get
    while (b != END_OF_STRING) {str += b; b = buffer.get}
    new String(str.toArray)
  }

  def ename(buffer: ByteBuffer): String = cstring(buffer)

  def boolean(buffer: ByteBuffer): JsBoolean = {
    val b = buffer.get
    if (b == 0) JsFalse else JsTrue
  }

  def int(buffer: ByteBuffer): JsInt = {
    val x = buffer.getInt
    // Remember to flip back the leading bit
    if (x == 0) JsInt.zero else JsInt(x ^ 0x80000000)
  }

  def long(buffer: ByteBuffer): JsLong = {
    val x = buffer.getLong
    // Remember to flip back the leading bit
    if (x == 0) JsLong.zero else JsLong(x ^ 0x8000000000000000L)
  }

  def double(buffer: ByteBuffer): JsDouble = {
    val x = buffer.getDouble
    if (x == 0.0) JsDouble.zero else JsDouble(x)
  }

  def date(buffer: ByteBuffer): JsDate = {
    JsDate(buffer.getLong)
  }

  def objectId(buffer: ByteBuffer): JsValue = {
    val id = new Array[Byte](BsonObjectId.size)
    buffer.get(id)
    JsObjectId(BsonObjectId(id))
  }

  def string(buffer: ByteBuffer): JsString = {
    val length = buffer.getInt
    val dst = new Array[Byte](length)
    buffer.get(dst)
    JsString(new String(dst, charset))
  }

  def binary(buffer: ByteBuffer): JsValue = {
    val length = buffer.getInt
    val subtype = buffer.get
    if (subtype == BINARY_SUBTYPE_UUID) {
      JsUUID(buffer.getLong, buffer.getLong)
    } else {
      val dst = new Array[Byte](length)
      buffer.get(dst)
      JsBinary(dst)
    }
  }
}
