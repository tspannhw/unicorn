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

package unicorn.index

import unicorn.bigtable.{Cell, Column}
import unicorn.util._

/**
 * Calculate the cell in the index table for a composite index (multiple columns) in the base table.
 *
 * @author Haifeng Li
 */
class CompositeIndexCodec(index: Index) extends IndexCodec {
  override def apply(row: Array[Byte], family: Array[Byte], columns: Seq[Column]): Cell = {
    var timestamp = 0L
    index.columns.foreach { indexColumn =>
      val column = columns.get(indexColumn.family).map(_.get(indexColumn.qualifier)).getOrElse(None) match {
        case Some(c) => if (c.timestamp > timestamp) timestamp = c.timestamp; c
        case None => throw new IllegalArgumentException("missing covered index column")
      }
      md5Encoder.update(column.value)
    }
    val hash = md5Encoder.digest

    val key = index.prefixedIndexRowKey(row, hash)

    Cell(key, IndexMeta.indexColumnFamily, row, IndexMeta.indexDummyValue, timestamp)

    if (index.unique)
      Cell(key, IndexMeta.indexColumnFamily, IndexMeta.uniqueIndexColumn, row, column.timestamp)
    else
      Cell(key, IndexMeta.indexColumnFamily, row, IndexMeta.indexDummyValue, column.timestamp)
  }
}