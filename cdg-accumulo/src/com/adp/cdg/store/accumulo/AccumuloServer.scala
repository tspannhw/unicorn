package com.adp.cdg.store.accumulo


import org.apache.accumulo.core.client.{Connector, ZooKeeperInstance}
import org.apache.accumulo.core.client.security.tokens.PasswordToken
import com.adp.cdg.store.DataStore
import com.adp.cdg.store.DataSet

class AccumuloServer(conn: Connector) extends DataStore {
  override def dataset(name: String, auth: String): DataSet = {
    new AccumuloTable(conn, name, auth)
  }
  
  override def createDataSet(name: String): DataSet = {
    conn.tableOperations.create(name)
    dataset(name)
  }
  
  override def dropDataSet(name: String): Unit = {
    conn.tableOperations.delete(name)
  }
}

object AccumuloServer {
  def apply(instance: String, zookeeper: String, user: String, password: String): AccumuloServer = {
    val inst = new ZooKeeperInstance(instance, zookeeper)
    val conn = inst.getConnector(user, new PasswordToken(password))
    new AccumuloServer(conn)
  }
}