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

package unicorn.unibase.graph

import java.nio.ByteBuffer

import unicorn.bigtable.{BigTable, Column, Row}
import unicorn.json._
import unicorn.oid.LongIdGenerator
import unicorn.unibase.UpdateOps
import unicorn.unibase.graph.Direction._
import unicorn.util.Logging

/** Graphs are mathematical structures used to model pairwise relations
  * between objects. A graph is made up of vertices (nodes) which are
  * connected by edges (arcs or lines). A graph may be undirected, meaning
  * that there is no distinction between the two vertices associated with
  * each edge, or its edges may be directed from one vertex to another.
  * Directed graphs are also called digraphs and directed edges are also
  * called arcs or arrows.
  *
  * A multigraph is a graph which is permitted to have multiple edges
  * (also called parallel edges), that is, edges that have the same end
  * nodes. The ability to support parallel edges simplifies modeling
  * scenarios where there can be multiple relationships (e.g., co-worker
  * and friend) between the same vertices.
  *
  * In a property graph, the generic mathematical graph is often extended
  * to support user defined objects attached to each vertex and edge.
  * The edges also have associated labels denoting the relationships,
  * which are important in a multigraph.
  *
  * Unicorn supports directed property multigraphs. Documents from different
  * tables can be added as vertices to a multigraph. It is also okay to add
  * vertices without corresponding to documents. Each relationship/edge
  * has a label and optional data (any valid JsValue, default value JsInt(1)).
  *
  * Unicorn stores graphs in adjacency lists. That is, a graph
  * is stored as a BigTable whose rows are vertices with their adjacency list.
  * The adjacency list of a vertex contains all of the vertex’s incident edges
  * (in and out edges are in different column families).
  *
  * Because large graphs are usually very sparse, an adjacency list is
  * significantly more space-efficient than an adjacency matrix.
  * Besides, the neighbors of each vertex may be listed efficiently with
  * an adjacency list, which is important in graph traversals.
  * With our design, it is also possible to
  * test whether two vertices are adjacent to each other
  * for a given relationship in constant time.
  *
  * @param table Graph adjacency list table.
  *
  * @author Haifeng Li
  */
class ReadOnlyGraph(val table: BigTable, documentVertexTable: BigTable) {

  import unicorn.unibase.$id

  /** Graph serializer. */
  val serializer = new GraphSerializer()

  /** The column qualifier of \$id field. */
  val idColumnQualifier = serializer.vertexSerializer.str2PathBytes($id)

  /** The graph name. */
  val name = table.name

  /** Returns the vertex properties and its both outgoing and incoming edges. */
  def apply(id: Long): Vertex = {
    apply(id, Both)
  }

  /** Returns the vertex properties and its adjacency list.
    *
    * @param id vertex id
    * @param direction what edges to load
    */
  def apply(id: Long, direction: Direction): Vertex = {
    val key = serializer.serialize(id)
    val families = direction match {
      case Outgoing => table.get(key, Seq((GraphVertexColumnFamily, Seq.empty), (GraphOutEdgeColumnFamily, Seq.empty)))
      case Incoming => table.get(key, Seq((GraphVertexColumnFamily, Seq.empty), (GraphInEdgeColumnFamily, Seq.empty)))
      case Both => table.get(key)
    }
    require(!families.isEmpty, s"Vertex $id doesn't exist in graph ${table.name}")

    serializer.deserializeVertex(Row(key, families))
  }

  /** Decodes vertex ID. */
  private[unicorn] def vertex(id: Array[Byte]): Long = {
    ByteBuffer.wrap(id).getLong
  }

  /** Returns the vertex of a document. */
  def apply(table: String, key: JsValue, tenant: JsValue = JsUndefined, direction: Direction = Both): Vertex = {
    val id = documentVertexTable(serializer.serialize(table, tenant, key), GraphVertexColumnFamily, name)
    require(id.isDefined, s"document vertex ($table, $key, $tenant) doesn't exist")

    apply(vertex(id.get))
  }

  /** Returns the edge between `from` and `to` with given label. */
  def apply(from: Long, label: String, to: Long): Option[JsValue] = {
    val fromKey = serializer.serialize(from)
    require(table.apply(fromKey, GraphVertexColumnFamily, idColumnQualifier).isDefined, s"Vertex $from doesn't exist in graph ${table.name}")

    val toKey = serializer.serialize(to)
    require(table.apply(toKey, GraphVertexColumnFamily, idColumnQualifier).isDefined, s"Vertex $to doesn't exist in graph ${table.name}")

    val columnPrefix = serializer.edgeSerializer.str2Bytes(label)
    val value = table(fromKey, GraphOutEdgeColumnFamily, serializer.serializeEdgeColumnQualifier(columnPrefix, to))

    value.map { bytes =>
      serializer.deserializeEdgeProperties(bytes)
    }
  }

  /** Returns a Gremlin traversal machine. */
  def traversal: Gremlin = {
    new Gremlin(new SimpleTraveler(this, direction = Direction.Both))
  }

  /** Returns a Gremlin traversal machine starting at the given vertex. */
  def v(id: Long): GremlinVertices = {
    val g = traversal
    g.v(id)
  }
}

/** Graph with update operators.
  *
  * @param idgen 64-bit ID generator for vertex id.
  */
class Graph(override val table: BigTable, documentVertexTable: BigTable, idgen: LongIdGenerator) extends ReadOnlyGraph(table, documentVertexTable) with UpdateOps with Logging {
  import unicorn.unibase.{$id, $tenant}

  /** For UpdateOps. */
  override val valueSerializer = serializer.vertexSerializer

  /** Returns the column family of a property. */
  override def familyOf(field: String): String = GraphVertexColumnFamily

  override def key(id: JsValue): Array[Byte] = {
    require(id.isInstanceOf[JsLong], "Graph vertex id must be 64-bit JsLong")
    serializer.serialize(id.asInstanceOf[JsLong].value)
  }

  /** Shortcut to addVertex. Returns the vertex properties object. */
  def update(id: Long, properties: JsObject): JsObject = {
    addVertex(id, properties)
    properties
  }

  /** Shortcut to addEdge. Returns the edge properties value. */
  def update(from: Long, label: String, to: Long, properties: JsValue): JsValue = {
    addEdge(from, label, to, properties)
    properties
  }

  /** Updates a vertex's properties. The supported update operators include
    *
    *  - \$set: Sets the value of a property of the vertex.
    *  - \$unset: Removes the specified property of the vertex.
    */
  def update(doc: JsObject): Unit = {
    val id = doc($id)

    require(id != JsNull && id != JsUndefined, s"missing ${$id}")
    require(id.isInstanceOf[JsLong], "${$id} must be JsLong")

    val vertex = id.asInstanceOf[JsLong].value

    val $set = doc("$set")
    require($set == JsUndefined || $set.isInstanceOf[JsObject], "$$set is not an object: " + $set)

    val $unset = doc("$unset")
    require($unset == JsUndefined || $unset.isInstanceOf[JsObject], "$$unset is not an object: " + $unset)

    if ($set.isInstanceOf[JsObject]) set(vertex, $set.asInstanceOf[JsObject])

    if ($unset.isInstanceOf[JsObject]) unset(vertex, $unset.asInstanceOf[JsObject])
  }

  /** Adds a vertex with predefined ID, which must be unique.
    *
    * @param id The unique vertex id. Throws exception if the vertex id exists.
    * @param properties Any vertex property data.
    */
  def addVertex(id: Long, properties: JsObject): Unit = {
    properties($id) = id

    val key = serializer.serialize(id)
    require(table.apply(key, GraphVertexColumnFamily, idColumnQualifier).isEmpty, s"Vertex $id already exists in graph ${table.name}")

    val columns = serializer.serializeVertex(properties)

    table.put(key, GraphVertexColumnFamily, columns: _*)
  }

  /** Creates a new vertex with automatic generated ID.
    * ID generator must be set up.
    *
    * @param properties Any vertex property data.
    * @return Vertex ID.
    */
  def addVertex(properties: JsObject): Long = {
    val id = idgen.next
    addVertex(id, properties)
    id
  }

  /** Creates a new vertex corresponding to a document in another table with automatic generated ID.
    * ID generator must be set up.
    *
    * @param table The table of name of document.
    * @param key The document id.
    * @param tenant The tenant id of document if the table is multi-tenanted.
    * @param properties Any vertex property data.
    * @return Vertex ID.
    */
  def addVertex(table: String, key: JsValue, tenant: JsValue = JsUndefined, properties: JsObject = JsObject()): Long = {
    val id = idgen.next

    properties($doc) = JsObject(
      $table -> table,
      $id -> key,
      $tenant -> tenant
    )

    addVertex(id, properties)
    documentVertexTable(serializer.serialize(table, tenant, key), GraphVertexColumnFamily, name) = serializer.serialize(id)

    id
  }

  /** Deletes a vertex and all associated edges. */
  def deleteVertex(id: Long): Unit = {
    val vertex = apply(id)

    val doc = vertex.properties($doc)
    if (doc != JsUndefined) {
      documentVertexTable.delete(serializer.serialize(doc($table), doc($tenant), doc($id)), GraphVertexColumnFamily, name)
    }

    vertex.inE.foreach { case (label, edges) =>
      edges.foreach { edge =>
        deleteEdge(edge.from, edge.label, edge.to)
      }
    }

    val key = serializer.serialize(id)
    table.delete(key)
  }

  def deleteVertex(table: String, key: JsValue, tenant: JsValue = JsUndefined): Unit = {
    val row = serializer.serialize(table, tenant, key)
    val id = documentVertexTable(row, GraphVertexColumnFamily, name)
    require(id.isDefined, s"document vertex ($table, $key, $tenant) doesn't exist")

    deleteVertex(vertex(id.get))
    this.table.delete(row)
  }

  /** Adds a directed edge. If the edge exists, the associated data will be overwritten.
    *
    * @param from vertex id.
    * @param label relationship label.
    * @param to vertex id.
    * @param properties optional data associated with the edge.
    */
  def addEdge(from: Long, label: String, to: Long, properties: JsValue = JsInt(1)): Unit = {
    val fromKey = serializer.serialize(from)
    require(table.apply(fromKey, GraphVertexColumnFamily, idColumnQualifier).isDefined, s"Vertex $from doesn't exist in graph ${table.name}")

    val toKey = serializer.serialize(to)
    require(table.apply(toKey, GraphVertexColumnFamily, idColumnQualifier).isDefined, s"Vertex $to doesn't exist in graph ${table.name}")

    val columnPrefix = serializer.edgeSerializer.str2Bytes(label)
    val value = serializer.serializeEdge(properties)

    table.put(fromKey, GraphOutEdgeColumnFamily, Column(serializer.serializeEdgeColumnQualifier(columnPrefix, to), value))
    table.put(toKey, GraphInEdgeColumnFamily, Column(serializer.serializeEdgeColumnQualifier(columnPrefix, from), value))
  }

  /** Deletes a directed edge.
    *
    * @param from vertex id.
    * @param label relationship label.
    * @param to vertex id.
    */
  def deleteEdge(from: Long, label: String, to: Long): Unit = {
    val fromKey = serializer.serialize(from)
    require(table.apply(fromKey, GraphVertexColumnFamily, idColumnQualifier).isDefined, s"Vertex $from doesn't exist in graph ${table.name}")

    val toKey = serializer.serialize(to)
    require(table.apply(toKey, GraphVertexColumnFamily, idColumnQualifier).isDefined, s"Vertex $to doesn't exist in graph ${table.name}")

    val columnPrefix = serializer.edgeSerializer.str2Bytes(label)

    table.delete(fromKey, GraphOutEdgeColumnFamily, serializer.serializeEdgeColumnQualifier(columnPrefix, to))
    table.delete(toKey, GraphInEdgeColumnFamily, serializer.serializeEdgeColumnQualifier(columnPrefix, from))
  }
}
