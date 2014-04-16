import scala.collection.mutable.Stack
import scala.io.Source
import scala.xml.pull._

def wikipedia(server: DataStore, table: DataSet, files: String*): Unit = {
  files.foreach { xmlFile =>
    val xml = new XMLEventReader(Source.fromFile(xmlFile))

    var id = 0
    var field: String = null
    var doc = Stack[Document]()
    for (event <- xml) {
      event match {
        case EvElemStart(_, "page", _, _) => {
          doc.push(Document(id.toString))
        }
        case EvElemEnd(_, "page") => {
          if (!doc.isEmpty) {
            val d = doc.pop
            assert(doc.isEmpty)
            // make sure we record all children’s changes
            d.json.value.foreach { case (key, value) => d(key) = value }
            println(d.id)
            d into table
            id = id + 1
          }
        }
        case e @ EvElemStart(_, tag, _, _) => {
          if (!doc.isEmpty) {
            if (field != null) {
              val child = Document(id.toString)
              val parent = doc.top
              parent(field) = child
              doc.push(child)
            }
            field = tag
          }
        }
        case e @ EvElemEnd(_, tag) => {
          if (field == null) {
            if (!doc.isEmpty) doc.pop
          }
          else field = null
        }
        case EvText(t) => {
          if (!doc.isEmpty && field != null) {
            val d = doc.top
            d(field) = t

            if (field == "id" && doc.size == 1) {
              d.id = t
            }
          }
        }
        case _ => // ignore
      }
    }
  }
}

val server = AccumuloServer("poc", "cdldvtitavap015:2181,cdldvtitavap016:2181,cdldvtitavap017:2181", "tester", "adpadp")
val table = server.dataset("wiki")

wikipedia(server, table, "/home/virtual/data/wiki/enwikinews-20140410-pages-articles-multistream.xml")