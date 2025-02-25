package mml.mmlclib.util

import mml.mmlclib.ast.*
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

class GraphDBPhase():

  val databaseDirectory     = Paths.get("target/neo4j-db")
  val DEFAULT_DATABASE_NAME = "mmlc-graph"

  val managementService = new DatabaseManagementServiceBuilder(databaseDirectory).build();
  val graphDb           = managementService.database(DEFAULT_DATABASE_NAME);

  Runtime.getRuntime.addShutdownHook(
    new Thread(() => managementService.shutdown())
  )

  def ingestModule(module: Module): Unit =

    try {

      val tx = graphDb.beginTx()
      // Create (or merge) a node for the module.
      val createModuleQuery =
        """
          |MERGE (m:Module {name: $name})
          |SET m.span = $span, m.visibility = $visibility
          |RETURN m
          |""".stripMargin

      val moduleParams: Map[String, Object] = Map(
        "name" -> module.name,
        "span" -> s"[${module.span.start.line}:${module.span.start.col} - ${module.span.end.line}:${module.span.end.col}]",
        "visibility" -> module.visibility.toString
      )

      tx.execute(createModuleQuery, moduleParams.asJava)

      // For each member in the module, create a corresponding node and relationship.
      module.members.foreach {
        case bnd: Bnd =>
          val query =
            """
              |MERGE (b:Binding {name: $name})
              |SET b.span = $span, b.typeAsc = $typeAsc
              |WITH b
              |MATCH (m:Module {name: $moduleName})
              |MERGE (m)-[:DECLARES]->(b)
              |""".stripMargin

          val params: Map[String, Object] = Map(
            "name" -> bnd.name,
            "span" -> s"[${bnd.span.start.line}:${bnd.span.start.col} - ${bnd.span.end.line}:${bnd.span.end.col}]",
            "typeAsc" -> bnd.typeAsc.map(_.toString).getOrElse("None"),
            "moduleName" -> module.name
          )

          tx.execute(query, params.asJava)

        case fn: FnDef =>
          val query =
            """
              |MERGE (f:Function {name: $name})
              |SET f.span = $span, f.paramCount = $paramCount
              |WITH f
              |MATCH (m:Module {name: $moduleName})
              |MERGE (m)-[:DECLARES]->(f)
              |""".stripMargin

          val params: Map[String, Object] = Map(
            "name" -> fn.name,
            "span" -> s"[${fn.span.start.line}:${fn.span.start.col} - ${fn.span.end.line}:${fn.span.end.col}]",
            "paramCount" -> fn.params.size.toString(),
            "moduleName" -> module.name
          )

          tx.execute(query, params.asJava)

        case _ => // Handle other member types if needed.
      }
      tx.commit()
    } catch {
      case e: Exception =>
        println(s"Error ingesting module: ${e.getMessage}")
    } finally {
      managementService.shutdown()
    }

    def shutdown(): Unit =
      managementService.shutdown()
